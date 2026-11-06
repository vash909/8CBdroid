package net.vash.ft8cbdroid.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import net.vash.ft8cbdroid.core.audio.*
import kotlin.math.pow

/** Foreground-only input. No playback, audio-focus changes, persisted recording or decoder calls. */
class AndroidMicrophoneCapture(context: Context) : MicrophoneCapture {
    private val app = context.applicationContext
    private val manager = app.getSystemService(AudioManager::class.java)
    private val devices = MutableStateFlow<List<AudioInput>>(emptyList())
    override val inputs = devices.asStateFlow()
    private val buffer = CaptureBuffer()
    private val mutex = Mutex()
    @Volatile private var closed = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    init { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())); refresh() }
    private fun supported() = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
        it.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG)
    }
    private fun label(device: AudioDeviceInfo): AudioInput {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset microphone"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog input"
            else -> "USB audio input"
        }
        return AudioInput(device.id, "$kind • ${device.productName} • ${device.id}")
    }
    private fun refresh() { if (!closed) devices.value = supported().map(::label) }
    private fun requirePermission() {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw CaptureException("Microphone permission is required. Allow it in Android app settings, then retry.")
    }
    @SuppressLint("MissingPermission") // Checked before construction and throughout the read loop.
    override suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit) = withContext(Dispatchers.IO) {
        check(mutex.tryLock()) { "Microphone capture is already running." }
        var record: AudioRecord? = null
        val effects = mutableListOf<AudioEffect>()
        buffer.clear()
        try {
            check(!closed)
            requirePermission()
            val selected = supported().firstOrNull { it.id == inputId }
                ?: throw CaptureException("The selected audio input is disconnected. Select an available input and retry.")
            val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            for (rate in listOf(48000, 44100)) {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0 || minimum > rate * 2) continue
                val candidate = try {
                    AudioRecord.Builder().setAudioSource(source)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum * 2, rate * 2 / 5)).build()
                } catch (_: IllegalArgumentException) { continue }
                if (candidate.state == AudioRecord.STATE_INITIALIZED && candidate.bufferSizeInFrames <= rate) {
                    record = candidate; break
                }
                candidate.release()
            }
            val recorder = record ?: throw CaptureException("This input could not open mono PCM16 at 48,000 or 44,100 Hz.")
            if (!recorder.setPreferredDevice(selected)) throw CaptureException("Android rejected the selected audio input. Select it again to retry.")
            val processing = mutableListOf<String>()
            fun effect(name: String, available: Boolean, create: () -> AudioEffect?) {
                if (!available) { processing += "$name: unavailable"; return }
                val value = try { create() } catch (_: RuntimeException) { null }
                if (value == null) { processing += "$name: unknown"; return }
                effects += value
                try { value.setEnabled(false); processing += "$name: ${if (value.enabled) "enabled" else "disabled"}" }
                catch (_: RuntimeException) { processing += "$name: unknown" }
            }
            effect("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(recorder.audioSessionId) }
            effect("Noise suppression", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(recorder.audioSessionId) }
            effect("Echo cancellation", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(recorder.audioSessionId) }
            val resampler = CaptureResampler(recorder.sampleRate)
            val input = ShortArray(recorder.sampleRate / 50)
            var inputSamples = 0L; var outputSamples = 0L; var clipped = 0L
            var peak = -120.0; var energy = 0.0; var metered = 0
            var lastData = SystemClock.elapsedRealtime()
            var lastUpdate = lastData
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw CaptureException("Android did not start the microphone. Stop other recording apps and retry.")
            val started = SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()
                if (closed) throw CaptureException("Audio capture was closed.")
                val count = recorder.read(input, 0, input.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) throw CaptureException("Audio input stopped (read error $count). Reconnect or select the input, then retry.")
                if (count > 0) {
                    lastData = now
                    val level = InputLevel.measure(input, count)
                    peak = maxOf(peak, level.peakDbfs)
                    // Combine the energy of every read in this UI interval, including quiet reads.
                    energy += 10.0.pow(level.rmsDbfs / 10) * count
                    metered += count; clipped += level.clipped; inputSamples += count
                    val pcm = resampler.process(input, count)
                    outputSamples += pcm.size; buffer.append(pcm)
                }
                if (now - lastData > 2000) throw CaptureException("No microphone samples arrived for two seconds. Check the input and retry.")
                if (now - lastUpdate >= 100) {
                    requirePermission()
                    if (supported().none { it.id == inputId }) throw CaptureException("The selected audio input was disconnected. Reconnect it and press Start microphone.")
                    val actual = recorder.routedDevice
                    if (actual != null && actual.id != inputId) throw CaptureException("Android changed the audio route. Select the intended input and press Start microphone.")
                    if (actual == null && now - started > 1000) throw CaptureException("Android did not confirm the selected audio route. Select an input and retry.")
                    val config = manager.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == recorder.audioSessionId }
                    if (Build.VERSION.SDK_INT >= 29 && config?.isClientSilenced == true)
                        throw CaptureException("Android silenced this microphone session. Check microphone privacy or other recording apps, then retry.")
                    if (actual != null) {
                        val deviceFormat = config?.format?.let { "${it.sampleRate} Hz / ${it.channelCount} channels / encoding ${it.encoding}" }
                            ?: "Device format unavailable"
                        val vendor = if (Build.VERSION.SDK_INT >= 29) config?.effects?.joinToString { it.name }?.ifEmpty { "none reported" }
                            ?: "unknown" else "unavailable on this Android version"
                        val route = CaptureRoute(label(actual), recorder.sampleRate, deviceFormat,
                            (if (source == MediaRecorder.AudioSource.UNPROCESSED) "Unprocessed" else "Voice recognition (fallback)") +
                                " • reported source: ${config?.clientAudioSource ?: "unknown"}",
                            processing.joinToString("; ") + "; reported device effects: $vendor. Vendor processing may remain.", recorder.bufferSizeInFrames)
                        val rms = if (metered > 0) maxOf(-120.0, 10 * kotlin.math.log10(energy / metered)) else -120.0
                        update(CaptureMetrics(route, inputSamples, outputSamples, peak, rms, clipped, minOf(outputSamples, 60000).toInt()))
                    }
                    lastUpdate = now; peak = -120.0; energy = 0.0; metered = 0
                }
                delay(5)
            }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { buffer.clear(); throw error }
        finally {
            runCatching { record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
            effects.forEach { runCatching { it.release() } }
            runCatching { record?.release() }
            mutex.unlock()
        }
    }
    override fun snapshot() = buffer.snapshot()
    override fun clear() = buffer.clear()
    override fun close() { closed = true; manager.unregisterAudioDeviceCallback(callback); buffer.clear() }
}

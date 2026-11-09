package net.vash.ft8cbdroid.audio

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.core.audio.*
import java.util.concurrent.atomic.AtomicReference
import net.vash.ft8cbdroid.core.time.ReceiveClock
import kotlin.math.abs

/** Explicit, foreground phone-speaker bench output. No radio, VOX, USB output or radio slot scheduler. */
class AndroidAudioPreview(context: Context) : ScheduledAudioPreview {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val devices = MutableStateFlow<List<PreviewOutput>>(emptyList())
    override val outputs = devices.asStateFlow()
    private val activeAbort = AtomicReference<AtomicReference<String?>?>(null)
    private val gate = kotlinx.coroutines.sync.Mutex()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private var inventory = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    init { refresh(); manager.registerAudioDeviceCallback(callback, handler) }
    private fun refresh() {
        if (closed) return
        val all = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val next = all.map { it.id }.toSet()
        if (next != inventory) activeAbort.get()?.compareAndSet(null, "Audio devices changed. Preview stopped; select the output and retry.")
        if (next != inventory) generation++
        inventory = next
        devices.value = all.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            .map { PreviewOutput(it.id, "Phone speaker · ${it.productName}") }
    }
    override suspend fun play(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings, update: (PreviewReport) -> Unit) {
        output(previewSamples(slot, settings), outputId, null, null, false, update)
    }
    override suspend fun measureLatency(outputId: Int): OutputLatency =
        requireNotNull(output(FloatArray(12_000), outputId, null, null, true) {})
    override suspend fun testTone(outputId: Int, frequencyHz: Float, peakAmplitude: Float) {
        output(phoneTestTone(frequencyHz, peakAmplitude), outputId, null, null, false) {}
    }
    override suspend fun playScheduled(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings,
        plan: ScheduledSlot, clock: ReceiveClock, update: (PreviewReport) -> Unit) {
        output(previewSamples(slot, settings), outputId, plan, clock, false, update)
    }
    private suspend fun output(pcm: FloatArray, outputId: Int, plan: ScheduledSlot?, clock: ReceiveClock?,
        measure: Boolean, update: (PreviewReport) -> Unit): OutputLatency? = withContext(Dispatchers.IO) {
        if (plan != null && pcm.take(9600).any { it != 0f })
            throw PreviewException("Scheduled playback requires the generated 0.8-second leading silence.")
        check(gate.tryLock()) { "Another audio preview is stopping. Retry when it has finished." }
        val abort = AtomicReference<String?>(null)
        var track: AudioTrack? = null
        var focus: AudioFocusRequest? = null
        val outputGeneration = generation
        try {
            check(!closed) { "Audio preview is closed." }
            val output = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.id == outputId && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } ?: throw PreviewException("Selected speaker is unavailable. Select an output again.")
            activeAbort.set(abort)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN)
                        abort.compareAndSet(null, "Audio focus was interrupted. Preview stopped; restart explicitly.")
                }, handler).build()
            focus = request
            if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                throw PreviewException("Audio focus was denied. Stop other audio and retry.")
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setSampleRate(12_000).build()
            val minimum = AudioTrack.getMinBufferSize(12_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minimum <= 0) throw PreviewException("12 kHz mono playback is unavailable on this device.")
            val player = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 2, 4800)).build()
            track = player
            if (player.state != AudioTrack.STATE_INITIALIZED || !player.setPreferredDevice(output))
                throw PreviewException("Could not select the phone speaker. Select it again and retry.")
            // Prefill the same 100 ms of silence for measurement, immediate and scheduled sessions.
            var written = player.write(pcm, 0, minOf(1200, player.bufferSizeInFrames), AudioTrack.WRITE_NON_BLOCKING)
            if (written <= 0) throw PreviewException("Could not prefill the output. Retry explicitly.")
            val bootOffset = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            plan?.let { target ->
                val timingClock = requireNotNull(clock)
                withTimeout(35_000) {
                    while (true) {
                        ensureActive(); abort.get()?.let { throw PreviewException(it) }
                        target.latency.validate(SystemClock.elapsedRealtimeNanos(), outputId, generation)
                        val remaining = target.remaining(timingClock)
                        if (remaining <= 0) break
                        delay(if (remaining > 20_000_000L) 5 else 1)
                    }
                }
            }
            val commandNanos = SystemClock.elapsedRealtimeNanos()
            val estimator = PresentationEstimator(commandNanos)
            val stamp = AudioTimestamp()
            var errorMillis: Double? = null
            var timingConfirmed = false
            var routed = false
            var lastProgress = SystemClock.elapsedRealtime()
            var lastPublish = 0L
            val routeStart = SystemClock.elapsedRealtime()
            if (plan != null && commandNanos - plan.commandNanos > 20_000_000L)
                throw SchedulingException("The slot deadline was missed. Queue again explicitly.")
            player.play()
            withTimeout(18_000) {
                while (written < pcm.size || player.playbackHeadPosition.toLong() < written) {
                    ensureActive()
                    abort.get()?.let { throw PreviewException(it) }
                    plan?.validate(requireNotNull(clock))
                    if (abs(SystemClock.elapsedRealtimeNanos() - System.nanoTime() - bootOffset) > 20_000_000L)
                        throw PreviewException("Device sleep changed the audio clock. Queue again explicitly.")
                    if (player.getTimestamp(stamp)) {
                        estimator.observe(stamp.framePosition, stamp.nanoTime + bootOffset)
                        if (plan != null && !timingConfirmed) {
                            val estimate = runCatching { estimator.estimate(outputId, outputGeneration, SystemClock.elapsedRealtimeNanos()) }.getOrNull()
                            if (estimate != null) {
                                errorMillis = (commandNanos + estimate.nanos - plan.firstFrameNanos) / 1e6
                                if (abs(errorMillis!!) > 75) throw PreviewException("Output missed the UTC target. Preview stopped before the FT8 signal; measure latency and retry.")
                                timingConfirmed = true
                            }
                        }
                    }
                    if (plan != null && !timingConfirmed && SystemClock.elapsedRealtimeNanos() - commandNanos > 600_000_000L)
                        throw PreviewException("Output timing could not be confirmed before the signal. Measure latency again.")
                    val route = player.routedDevice
                    if (route != null) {
                        if (route.id != outputId) throw PreviewException("Android changed the audio route. Preview stopped without fallback.")
                        routed = true
                    } else if (routed || SystemClock.elapsedRealtime() - routeStart > 500)
                        throw PreviewException("Audio route was lost. Select the output and retry.")
                    if (written < pcm.size) {
                        val count = player.write(pcm, written, minOf(240, pcm.size - written), AudioTrack.WRITE_NON_BLOCKING)
                        if (count < 0) throw PreviewException("Audio output failed. Select the output and retry.")
                        if (count > 0) { written += count; lastProgress = SystemClock.elapsedRealtime() }
                        else if (SystemClock.elapsedRealtime() - lastProgress > 1500)
                            throw PreviewException("Audio output stalled. Stop other audio and retry.")
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPublish >= 100) {
                        update(PreviewReport("Phone speaker · ${output.productName}", player.sampleRate, written, player.playbackHeadPosition.toLong(), errorMillis, plan?.utcMillis))
                        lastPublish = now
                    }
                    delay(5)
                }
            }
            if (!routed) throw PreviewException("Android did not confirm the selected speaker.")
            update(PreviewReport("Phone speaker · ${output.productName}", player.sampleRate, written, player.playbackHeadPosition.toLong(), errorMillis, plan?.utcMillis))
            if (measure) estimator.estimate(outputId, outputGeneration, SystemClock.elapsedRealtimeNanos()) else null
        } catch (error: SchedulingException) { throw PreviewException(error.message ?: "Scheduling failed. Queue again explicitly.")
        } catch (_: TimeoutCancellationException) {
            throw PreviewException("Audio preview exceeded its deadline. Select the output and retry.")
        } finally {
            // Short bounded fade and queue discard on Stop/focus/route loss; never resume on focus gain.
            withContext(NonCancellable) {
                track?.let { player ->
                    runCatching {
                        repeat(4) { player.setVolume((3 - it) / 4f); delay(3) }
                        player.pause(); player.flush(); player.stop()
                    }
                    runCatching { player.release() }
                }
                focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
                activeAbort.compareAndSet(abort, null); gate.unlock()
            }
        }
    }
    override fun close() {
        closed = true; activeAbort.get()?.compareAndSet(null, "Audio preview closed.")
        manager.unregisterAudioDeviceCallback(callback)
    }
}

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

/** Explicit, foreground phone-speaker bench output. No radio, VOX, USB output or slot scheduler. */
class AndroidAudioPreview(context: Context) : AudioPreview {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val devices = MutableStateFlow<List<PreviewOutput>>(emptyList())
    override val outputs = devices.asStateFlow()
    private val activeAbort = AtomicReference<AtomicReference<String?>?>(null)
    private val gate = kotlinx.coroutines.sync.Mutex()
    @Volatile private var closed = false
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
        inventory = next
        devices.value = all.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            .map { PreviewOutput(it.id, "Phone speaker · ${it.productName}") }
    }
    override suspend fun play(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings, update: (PreviewReport) -> Unit) = withContext(Dispatchers.IO) {
        check(gate.tryLock()) { "Another audio preview is stopping. Retry when it has finished." }
        val abort = AtomicReference<String?>(null)
        var track: AudioTrack? = null
        var focus: AudioFocusRequest? = null
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
            val pcm = previewSamples(slot, settings)
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setSampleRate(12_000).build()
            val minimum = AudioTrack.getMinBufferSize(12_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minimum <= 0) throw PreviewException("12 kHz mono playback is unavailable on this device.")
            val player = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 2, 4800)).build()
            track = player
            if (player.state != AudioTrack.STATE_INITIALIZED || !player.setPreferredDevice(output))
                throw PreviewException("Could not select the phone speaker. Select it again and retry.")
            var written = 0
            var routed = false
            var lastProgress = SystemClock.elapsedRealtime()
            var lastPublish = 0L
            val routeStart = SystemClock.elapsedRealtime()
            player.play()
            withTimeout(18_000) {
                while (written < pcm.size || player.playbackHeadPosition.toLong() < written) {
                    ensureActive()
                    abort.get()?.let { throw PreviewException(it) }
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
                        update(PreviewReport("Phone speaker · ${output.productName}", player.sampleRate, written, player.playbackHeadPosition.toLong()))
                        lastPublish = now
                    }
                    delay(5)
                }
            }
            if (!routed) throw PreviewException("Android did not confirm the selected speaker.")
            update(PreviewReport("Phone speaker · ${output.productName}", player.sampleRate, written, player.playbackHeadPosition.toLong()))
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

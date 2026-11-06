package net.vash.ft8cbdroid.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vash.ft8cbdroid.core.audio.*

enum class CapturePhase { IDLE, STARTING, CAPTURING, STOPPING }
data class CaptureUiState(
    val inputs: List<AudioInput> = emptyList(),
    val selectedId: Int? = null,
    val phase: CapturePhase = CapturePhase.IDLE,
    val awaitingPermission: Boolean = false,
    val exporting: Boolean = false,
    val metrics: CaptureMetrics? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val canConfigure get() = phase == CapturePhase.IDLE && !awaitingPermission && !exporting
    val canStart get() = canConfigure && inputs.any { it.id == selectedId }
    val canExport get() = canConfigure && (metrics?.retainedSamples ?: 0) > 0
}
fun interface CaptureWriter { suspend fun write(document: String, bytes: ByteArray) }

/** Each start is explicit. Permission callbacks and late worker updates cannot restart stopped capture. */
class CaptureViewModel(private val capture: MicrophoneCapture, private val writer: CaptureWriter) : ViewModel() {
    private val state = MutableStateFlow(CaptureUiState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var discard = false
    private var pendingExport: ByteArray? = null
    init {
        viewModelScope.launch {
            capture.inputs.collect { inputs ->
                state.value = state.value.copy(inputs = inputs, selectedId = state.value.selectedId ?: inputs.firstOrNull()?.id)
            }
        }
    }
    fun select(id: Int) {
        if (!state.value.canConfigure || state.value.inputs.none { it.id == id } || state.value.selectedId == id) return
        capture.clear(); pendingExport = null
        state.value = state.value.copy(selectedId = id, metrics = null, error = null, notice = null)
    }
    /** Return true only when the Activity must launch Android's permission request. */
    fun requestStart(permissionGranted: Boolean): Boolean {
        if (!state.value.canStart) return false
        pendingExport = null
        capture.clear()
        state.value = state.value.copy(metrics = null, error = null, notice = null)
        if (!permissionGranted) {
            state.value = state.value.copy(awaitingPermission = true)
            return true
        }
        start(); return false
    }
    fun permissionResult(granted: Boolean) {
        if (!state.value.awaitingPermission) return
        state.value = state.value.copy(awaitingPermission = false)
        if (granted) start() else state.value = state.value.copy(
            error = "Microphone permission denied. Press Start microphone to retry, or allow Microphone in Android app settings.")
    }
    private fun start() {
        val id = state.value.selectedId ?: return
        if (!state.value.canStart) return
        val ticket = ++generation
        discard = false; capture.clear()
        state.value = state.value.copy(phase = CapturePhase.STARTING, metrics = null)
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                capture.capture(id) { metrics ->
                    withContext(Dispatchers.Main.immediate) {
                        if (ticket == generation && state.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING))
                            state.value = state.value.copy(phase = CapturePhase.CAPTURING, metrics = metrics)
                    }
                }
                throw CaptureException("Microphone capture ended. Press Start microphone to retry.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                capture.clear()
                state.value = state.value.copy(metrics = null, error = if (error is CaptureException) error.message
                    else "Could not capture the microphone. Check permission and the input, then retry.")
            }
        }
        job = next
        // Completion also runs if Stop cancels before the coroutine body ever starts.
        next.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (job === next) {
                    if (discard) capture.clear()
                    state.value = state.value.copy(phase = CapturePhase.IDLE, metrics = state.value.metrics.takeUnless { discard })
                    job = null
                }
            }
        }
        next.start()
    }
    fun stop(reason: String = "Microphone stopped.", clear: Boolean = false) {
        discard = discard || clear
        val wasPending = state.value.awaitingPermission
        state.value = state.value.copy(awaitingPermission = false)
        if (job != null) {
            state.value = state.value.copy(phase = CapturePhase.STOPPING, notice = reason)
            job?.cancel()
        } else {
            if (clear) capture.clear()
            state.value = state.value.copy(metrics = state.value.metrics.takeUnless { clear }, notice = reason.takeIf { wasPending } ?: state.value.notice)
        }
    }
    fun prepareExport(): Boolean {
        if (!state.value.canExport) return false
        pendingExport = CaptureWav.encode(capture.snapshot())
        return true
    }
    fun export(document: String?) {
        val bytes = pendingExport ?: return
        pendingExport = null
        if (document == null) { state.value = state.value.copy(notice = "No microphone WAV saved."); return }
        state.value = state.value.copy(exporting = true)
        viewModelScope.launch {
            try { writer.write(document, bytes); state.value = state.value.copy(notice = "Microphone WAV saved (12,000 Hz mono PCM16).", error = null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = "Could not save the microphone WAV. Select a destination and retry.") }
            finally { state.value = state.value.copy(exporting = false) }
        }
    }
    override fun onCleared() { job?.cancel(); capture.close() }
    companion object {
        fun factory(context: Context, resolver: ContentResolver) = viewModelFactory {
            initializer { CaptureViewModel(AndroidMicrophoneCapture(context), CaptureWriter { document, bytes ->
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(Uri.parse(document), "wt") ?: error("No output stream")
                    output.use { it.write(bytes) }
                }
            }) }
        }
    }
}

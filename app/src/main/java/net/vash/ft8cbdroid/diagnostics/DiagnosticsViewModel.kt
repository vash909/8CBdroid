package net.vash.ft8cbdroid.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vash.ft8cbdroid.core.diagnostics.*

/** Storage and document IO are adapters; domain recording never opens a microphone or network. */
interface DiagnosticsArchive {
    suspend fun read(): DiagnosticReport?
    suspend fun write(report: DiagnosticReport)
    suspend fun delete()
    suspend fun export(document: String, report: DiagnosticReport)
}
data class DiagnosticsState(
    val loading: Boolean = true, val busy: Boolean = false, val exporting: Boolean = false,
    val choosing: Boolean = false, val report: DiagnosticReport? = null,
    val error: String? = null, val notice: String? = null,
) {
    val active get() = report?.end == DiagnosticEnd.RUNNING
    val canStart get() = !loading && !busy && report == null && error == null
}

class DiagnosticsViewModel(
    private val archive: DiagnosticsArchive,
    private val observation: () -> DiagnosticObservation,
    private val resources: suspend () -> DiagnosticResources,
    private val now: () -> Long,
) : ViewModel() {
    private val state = MutableStateFlow(DiagnosticsState())
    val uiState = state.asStateFlow()
    private val recorder = SessionDiagnostics()
    private var sampleJob: Job? = null
    private var pending: DiagnosticReport? = null
    val sink = ReceiveDiagnosticSink { event, millis -> recorder.event(now(), event, millis) }
    private var loadJob: Job? = null
    init { load() }
    fun load() {
        if (state.value.active || state.value.busy || state.value.exporting || loadJob?.isActive == true) return
        state.value = state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val old = archive.read()
                val report = old?.let { if (it.end == DiagnosticEnd.RUNNING) it.copy(end = DiagnosticEnd.INTERRUPTED) else it }
                if (report != null && old != report) archive.write(report)
                state.value = DiagnosticsState(loading = false, report = report)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = DiagnosticsState(loading = false, error = "Could not read diagnostics. Retry loading or delete the report.") }
        }
    }
    fun start() {
        if (!state.value.canStart || state.value.exporting || state.value.choosing) return
        recorder.start(now())
        state.value = state.value.copy(report = recorder.snapshot(), error = null, notice = null)
        sampleJob = viewModelScope.launch {
            try {
                while (isActive) {
                    // Snapshot controls on Main; obtain low-rate resource metrics away from the audio reader.
                    val input = observation()
                    val measured = resources()
                    recorder.sample(now(), input, measured)
                    val report = recorder.snapshot()
                    archive.write(report)
                    state.value = state.value.copy(report = report)
                    if (report.end != DiagnosticEnd.RUNNING) break
                    delay(SessionDiagnostics.SAMPLE_INTERVAL_MILLIS)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val report = recorder.stop(now())
                state.value = state.value.copy(report = report, error = "Diagnostics stopped because a checkpoint could not be saved. Export or delete the report, then retry.")
            }
        }
    }
    fun stop() {
        if (!state.value.active || state.value.busy) return
        state.value = state.value.copy(busy = true)
        val previous = sampleJob; previous?.cancel()
        viewModelScope.launch {
            try {
                previous?.join()
                val report = recorder.stop(now()); state.value = state.value.copy(report = report)
                archive.write(report)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = "Could not save the final diagnostic checkpoint. Export or retry deletion.") }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
    /** Joining the writer prevents a cancelled checkpoint from recreating the deleted report. */
    fun delete() {
        if (state.value.loading || state.value.busy || state.value.exporting) return
        pending = null
        state.value = state.value.copy(busy = true, choosing = false)
        val previous = sampleJob; previous?.cancel(); recorder.stop(now())
        viewModelScope.launch {
            try {
                previous?.join(); archive.delete()
                state.value = DiagnosticsState(loading = false, notice = "Diagnostic report deleted from this app.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(busy = false, report = state.value.report?.let { if (it.end == DiagnosticEnd.RUNNING) recorder.snapshot() else it }, error = "Could not delete diagnostics. Retry deletion.") }
        }
    }
    fun prepareExport(): Boolean {
        val current = state.value
        if (current.loading || current.busy || current.exporting || current.choosing || current.report == null) return false
        pending = if (current.active) recorder.snapshot() else current.report
        state.value = current.copy(choosing = true, error = null, notice = null)
        return true
    }
    fun export(document: String?) {
        val snapshot = pending ?: return
        pending = null; state.value = state.value.copy(choosing = false)
        if (document == null) { state.value = state.value.copy(notice = "No diagnostic report exported."); return }
        state.value = state.value.copy(exporting = true, error = null, notice = null)
        viewModelScope.launch {
            try { archive.export(document, snapshot); state.value = state.value.copy(notice = "Diagnostic report exported.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = "Could not export the report. Choose a destination and retry.") }
            finally { state.value = state.value.copy(exporting = false) }
        }
    }
    override fun onCleared() { sampleJob?.cancel() }
}

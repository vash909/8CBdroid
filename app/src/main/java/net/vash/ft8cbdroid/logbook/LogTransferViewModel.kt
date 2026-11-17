package net.vash.ft8cbdroid.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vash.ft8cbdroid.core.logbook.*
import java.util.UUID

enum class LogFileKind { ADIF, BACKUP }
data class LogDocumentRequest(val id: String = UUID.randomUUID().toString(), val kind: LogFileKind, val read: Boolean)
interface LogDocuments {
    suspend fun read(document: String): ByteArray
    suspend fun write(document: String, bytes: ByteArray)
    suspend fun share(bytes: ByteArray): String
}
data class LogImportPreview(val before: QsoLogSnapshot, val incoming: QsoLogSnapshot, val kind: LogFileKind,
    val rows: List<LogMergeRow>, val issues: List<AdifIssue> = emptyList(), val warnings: List<AdifIssue> = emptyList())
data class LogTransferState(
    val busy: Boolean = false, val committing: Boolean = false, val choosing: Boolean = false,
    val request: LogDocumentRequest? = null, val shareUri: String? = null,
    val preview: LogImportPreview? = null, val selected: Set<String> = emptySet(), val reviewed: Boolean = false,
    val policy: AdifBandPolicy = AdifBandPolicy.WSJT_CB_11M,
    val error: String? = null, val notice: String? = null, val retryExport: Boolean = false,
) { val canApply get() = !busy && !choosing && reviewed && selected.isNotEmpty() && preview != null }

/** Frozen export snapshots; parsing never mutates the log. A confirmed merge is one accepted transaction. */
class LogTransferViewModel(private val repository: QsoLogRepository, private val documents: LogDocuments,
    private val ownCall: () -> String?, private val applied: () -> Unit,
    private val worker: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private val state = MutableStateFlow(LogTransferState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var selection: LogDocumentRequest? = null
    private var frozen: Pair<LogFileKind, ByteArray>? = null
    fun policy(value: AdifBandPolicy) { if (!state.value.busy && !state.value.choosing) state.value = state.value.copy(policy = value) }
    fun choose(kind: LogFileKind) {
        if (state.value.busy || state.value.choosing) return
        cancel()
        val request = LogDocumentRequest(kind = kind, read = true); selection = request
        state.value = state.value.copy(request = request, choosing = true)
    }
    fun requestLaunched(id: String) { if (state.value.request?.id == id) state.value = state.value.copy(request = null) }
    fun cancel() {
        if (state.value.committing) return
        generation++; job?.cancel(); job = null; selection = null; frozen = null
        state.value = state.value.copy(busy = false, choosing = false, request = null, preview = null, selected = emptySet(), reviewed = false,
            shareUri = null, retryExport = false, error = null, notice = "Transfer cancelled. The log was not changed.")
    }
    fun prepareExport(kind: LogFileKind, sharing: Boolean = false) {
        if (state.value.busy || state.value.choosing) return
        cancel(); val ticket = generation; val policy = state.value.policy
        state.value = state.value.copy(busy = true, notice = null)
        job = viewModelScope.launch {
            try {
                val snapshot = repository.read()
                val bytes = withContext(worker) { if (kind == LogFileKind.ADIF) Adif.encode(snapshot.records, policy) else LogBackup.encode(snapshot) }
                ensureActive(); if (ticket != generation) return@launch
                frozen = kind to bytes
                if (sharing) {
                    val uri = documents.share(bytes)
                    if (ticket == generation) state.value = state.value.copy(shareUri = uri)
                } else requestExport()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (ticket == generation) state.value = state.value.copy(error = error.message ?: "Could not prepare the log file. Retry.") }
            finally { if (ticket == generation) state.value = state.value.copy(busy = false) }
        }
    }
    private fun requestExport() {
        val kind = frozen?.first ?: return
        val request = LogDocumentRequest(kind = kind, read = false); selection = request
        state.value = state.value.copy(request = request, choosing = true, error = null)
    }
    fun retryExport() { if (!state.value.busy && !state.value.choosing && frozen != null) requestExport() }
    fun shared(error: String? = null) { state.value = state.value.copy(shareUri = null, error = error) }
    fun document(document: String?) {
        val request = selection ?: return
        selection = null; state.value = state.value.copy(choosing = false, request = null)
        if (document == null) { frozen = null; state.value = state.value.copy(notice = "No log file selected.", retryExport = false); return }
        val ticket = generation; val fallback = ownCall(); val bytes = frozen?.second
        if (!request.read && bytes == null) { state.value = state.value.copy(error = "This transfer was interrupted. Start it again."); return }
        state.value = state.value.copy(busy = true, notice = null, error = null)
        job = viewModelScope.launch {
            try {
                if (request.read) {
                    val input = documents.read(document)
                    val parsed = withContext(worker) {
                        if (request.kind == LogFileKind.ADIF) Adif.decode(input, fallback) else null
                    }
                    val incoming = if (parsed != null) QsoLogSnapshot(parsed.records) else withContext(worker) { LogBackup.decode(input) }
                    val before = repository.read()
                    val rows = withContext(worker) { logMergeRows(before, incoming, request.kind == LogFileKind.ADIF) }
                    if (ticket == generation) state.value = state.value.copy(preview = LogImportPreview(before, incoming, request.kind, rows, parsed?.issues.orEmpty(), parsed?.warnings.orEmpty()),
                        selected = rows.filter { it.status == LogEntryStatus.NEW }.map { it.id }.toSet(), reviewed = false)
                } else {
                    documents.write(document, requireNotNull(bytes))
                    if (ticket == generation) { frozen = null; state.value = state.value.copy(notice = "Log file saved.", retryExport = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == generation) state.value = state.value.copy(error = if (request.read) error.message ?: "Could not read the log file. Retry." else "Could not save the log file. Choose a destination and retry.", retryExport = !request.read)
            } finally { if (ticket == generation) state.value = state.value.copy(busy = false) }
        }
    }
    fun select(id: String, selected: Boolean) {
        if (state.value.busy) return
        val row = state.value.preview?.rows?.find { it.id == id } ?: return
        if (row.status !in setOf(LogEntryStatus.NEW, LogEntryStatus.DUPLICATE)) return
        state.value = state.value.copy(selected = if (selected) state.value.selected + id else state.value.selected - id, reviewed = false)
    }
    fun reviewed(value: Boolean) { if (!state.value.busy) state.value = state.value.copy(reviewed = value) }
    fun refreshPreview() {
        val old = state.value.preview ?: return
        if (state.value.busy) return
        val ticket = generation; state.value = state.value.copy(busy = true, reviewed = false)
        job = viewModelScope.launch {
            try {
                val before = repository.read(); val rows = withContext(worker) { logMergeRows(before, old.incoming, old.kind == LogFileKind.ADIF) }
                if (ticket == generation) state.value = state.value.copy(preview = old.copy(before = before, rows = rows),
                    selected = rows.filter { it.status == LogEntryStatus.NEW }.map { it.id }.toSet(), error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == generation) state.value = state.value.copy(error = "Could not refresh the log preview. Retry.") }
            finally { if (ticket == generation) state.value = state.value.copy(busy = false) }
        }
    }
    fun apply() {
        if (!state.value.canApply) return
        val preview = requireNotNull(state.value.preview); val selected = state.value.selected
        val rows = preview.rows.filter { it.id in selected }
        val command = LogMergeCommand(preview.before, QsoLogSnapshot(rows.mapNotNull { it.record }, rows.mapNotNull { it.pending }),
            rows.filter { it.status == LogEntryStatus.DUPLICATE }.map { it.id }.toSet(), preview.kind == LogFileKind.ADIF)
        state.value = state.value.copy(busy = true, committing = true, error = null)
        job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    repository.merge(command); applied()
                    state.value = state.value.copy(preview = null, selected = emptySet(), reviewed = false, notice = "Imported ${rows.size} reviewed entries. Existing entries were kept.")
                } catch (error: Exception) { state.value = state.value.copy(error = error.message ?: "Could not import the reviewed entries. Retry or refresh the preview.") }
                finally { state.value = state.value.copy(busy = false, committing = false) }
            }
        }
    }
}

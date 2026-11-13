package net.vash.ft8cbdroid.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.math.BigDecimal
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.logbook.*

fun mhz(hz: Long): String = BigDecimal.valueOf(hz, 6).stripTrailingZeros().toPlainString()
data class LogDraft(
    val id: String, val completionId: String? = null, val expectedVersion: Int? = null,
    val ownCall: String = "", val peerCall: String = "", val ownGrid: String = "", val peerGrid: String = "",
    val startUtc: String, val endUtc: String, val dialMhz: String = "", val sent: String = "", val received: String = "",
    val notes: String = "", val dialConfirmed: Boolean = false,
) {
    fun command(): SaveQso {
        require(dialConfirmed) { "Confirm the actual radio dial frequency before saving." }
        val start = runCatching { Instant.parse(startUtc.trim()).toEpochMilli() }.getOrElse { error("Enter start UTC in ISO format, ending in Z.") }
        val end = runCatching { Instant.parse(endUtc.trim()).toEpochMilli() }.getOrElse { error("Enter end UTC in ISO format, ending in Z.") }
        require(startUtc.trim().endsWith('Z') && endUtc.trim().endsWith('Z')) { "UTC times must end in Z." }
        val dial = runCatching { BigDecimal(dialMhz.trim()).movePointRight(6).longValueExact() }
            .getOrElse { error("Enter MHz with at most six decimal places.") }
        fun report(v: String): Int? = if (v.isBlank()) null else requireNotNull(v.trim().toIntOrNull()) { "Enter a whole-number report or leave it blank." }
        return SaveQso(id, QsoDetails(ownCall, peerCall, start, end, ownGrid, peerGrid, dial, report(sent), report(received), notes)
            .normalized().also { it.validate() }, completionId, expectedVersion, dialConfirmed)
    }
    companion object {
        fun from(id: String, d: QsoDetails, completionId: String? = null, version: Int? = null) = LogDraft(id, completionId, version,
            d.ownCall, d.peerCall, d.ownGrid, d.peerGrid, Instant.ofEpochMilli(d.startedUtcMillis).toString(),
            Instant.ofEpochMilli(d.endedUtcMillis).toString(), mhz(d.dialHz), d.sentReport?.toString().orEmpty(),
            d.receivedReport?.toString().orEmpty(), d.notes)
    }
}
data class LogbookState(
    val snapshot: QsoLogSnapshot = QsoLogSnapshot(), val loaded: Boolean = false, val busy: Boolean = false,
    val query: String = "", val draft: LogDraft? = null, val error: String? = null, val notice: String? = null,
    val unsavedCompletions: Int = 0,
) {
    val visible get() = snapshot.search(query)
    val workedAvailable get() = loaded && error == null && unsavedCompletions == 0
}

class LogbookViewModel(private val repository: QsoLogRepository) : ViewModel() {
    companion object { fun factory(repository: QsoLogRepository) = viewModelFactory { initializer { LogbookViewModel(repository) } } }
    private val state = MutableStateFlow(LogbookState())
    val uiState = state.asStateFlow()
    private val commands = Mutex()
    private val outbox = linkedMapOf<String, QsoCompletion>()
    init { reload() }
    private fun request(action: suspend () -> Unit) {
        // Start before returning from a completion callback; accepted writes survive Activity teardown.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                commands.withLock {
                    state.value = state.value.copy(busy = true, error = null)
                    try { action() }
                    catch (e: Exception) {
                        state.value = state.value.copy(error = if (e is IllegalArgumentException || e is IllegalStateException)
                            e.message ?: "The log action could not be completed." else "Could not access the log. Existing data was kept. Retry.")
                    } finally { state.value = state.value.copy(busy = false, unsavedCompletions = outbox.size) }
                }
            }
        }
    }
    private suspend fun refresh() {
        val snapshot = try { repository.read() } catch (e: Exception) { throw IOException("The existing log could not be read.", e) }
        state.value = state.value.copy(snapshot = snapshot, loaded = true)
    }
    fun reload() = request {
        for ((id, completion) in outbox.toMap()) { repository.stage(completion); outbox.remove(id) }
        refresh()
    }
    fun completed(completion: QsoCompletion) {
        completion.validate()
        outbox[completion.id] = completion
        state.value = state.value.copy(unsavedCompletions = outbox.size)
        reload()
    }
    fun query(value: String) { state.value = state.value.copy(query = value.take(100)) }
    fun manual(station: StationIdentity?, referenceHz: Long, now: Instant = Instant.now()) {
        if (state.value.busy || !state.value.loaded || state.value.draft != null) return
        val d = QsoDetails(station?.callsign.orEmpty(), "", now.toEpochMilli(), now.toEpochMilli(), station?.grid.orEmpty(), dialHz = referenceHz)
        state.value = state.value.copy(draft = LogDraft.from(UUID.randomUUID().toString(), d), error = null, notice = null)
    }
    fun review(id: String) {
        if (state.value.busy || state.value.draft != null) return
        val p = state.value.snapshot.pending.find { it.id == id } ?: return
        state.value = state.value.copy(draft = LogDraft.from(p.id, p.details, p.id), error = null, notice = null)
    }
    fun editRecord(id: String) {
        if (state.value.busy || state.value.draft != null) return
        val p = state.value.snapshot.records.find { it.id == id } ?: return
        state.value = state.value.copy(draft = LogDraft.from(p.id, p.details, p.completionId, p.version), error = null, notice = null)
    }
    fun edit(change: (LogDraft) -> LogDraft) {
        if (state.value.busy) return
        state.value.draft?.let { state.value = state.value.copy(draft = change(it), error = null) }
    }
    fun cancel() { if (!state.value.busy) state.value = state.value.copy(draft = null, error = null) }
    fun save() {
        if (state.value.busy) return
        val draft = state.value.draft ?: return
        val command = runCatching { draft.command() }.getOrElse { state.value = state.value.copy(error = it.message); return }
        request {
            repository.save(command)
            // Retain the same draft/identity until read-back succeeds, so Retry cannot create a second record.
            refresh()
            state.value = state.value.copy(draft = null, notice = "QSO saved.")
        }
    }
}

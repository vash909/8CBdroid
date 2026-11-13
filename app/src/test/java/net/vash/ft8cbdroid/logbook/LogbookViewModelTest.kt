package net.vash.ft8cbdroid.logbook

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.logbook.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val details = QsoDetails("1AT106", "26AT101", 15000, 90000, "JN44", dialHz = 27_265_000, sentReport = -7, receivedReport = -9)
    private val completion get() = QsoCompletion("exchange", details, 1500)
    private class Repository : QsoLogRepository {
        var snapshot = QsoLogSnapshot(); var failRead = false; var failStage = false; var failSave = false
        var saveGate: CompletableDeferred<Unit>? = null; val saves = mutableListOf<SaveQso>()
        override suspend fun read(): QsoLogSnapshot { if (failRead) throw IOException("read"); return snapshot }
        override suspend fun stage(completion: QsoCompletion) {
            if (failStage) throw IOException("stage")
            snapshot = snapshot.copy(pending = snapshot.pending.filterNot { it.id == completion.id } + completion)
        }
        override suspend fun save(command: SaveQso): QsoRecord {
            saves += command; saveGate?.await(); if (failSave) throw IOException("write")
            snapshot.records.find { it.id == command.id }?.let { return it }
            val record = QsoRecord(command.id, command.completionId, 1,
                if (command.completionId == null) QsoOrigin.MANUAL else QsoOrigin.LOCAL_AUDIO,
                command.details, if (command.completionId == null) null else 1500)
            snapshot = snapshot.copy(records = snapshot.records + record, pending = snapshot.pending.filterNot { it.id == command.completionId })
            return record
        }
    }
    private fun vm(repo: Repository): LogbookViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, LogbookViewModel.factory(repo))[LogbookViewModel::class.java]
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    @Test fun failedOpenDoesNotExposeWorkedOrDiscardDataAndRetryRecovers() = runTest(dispatcher) {
        val repo = Repository().also { it.failRead = true }; val model = vm(repo); runCurrent()
        assertFalse(model.uiState.value.loaded); assertFalse(model.uiState.value.workedAvailable)
        assertNotNull(model.uiState.value.error)
        repo.failRead = false; model.reload(); runCurrent()
        assertTrue(model.uiState.value.loaded); assertTrue(model.uiState.value.workedAvailable)
    }
    @Test fun failedCompletionWriteStaysRetryableAndOnlySavedContactsBecomeWorked() = runTest(dispatcher) {
        val repo = Repository(); val model = vm(repo); runCurrent(); repo.failStage = true
        model.completed(completion); runCurrent()
        assertEquals(1, model.uiState.value.unsavedCompletions); assertFalse(model.uiState.value.workedAvailable)
        assertTrue(repo.snapshot.records.isEmpty())
        repo.failStage = false; model.reload(); runCurrent()
        assertEquals(listOf(completion), model.uiState.value.snapshot.pending)
        assertTrue(model.uiState.value.snapshot.workedCalls("1AT106").isEmpty())
        model.review(completion.id); model.cancel()
        assertEquals(listOf(completion), repo.snapshot.pending)
        model.review(completion.id); model.edit { it.copy(dialConfirmed = true) }; model.save(); runCurrent()
        assertTrue(repo.snapshot.pending.isEmpty()); assertEquals(setOf("26AT101"), repo.snapshot.workedCalls("1AT106"))
    }
    @Test fun frequencyMustBeConfirmedAndFailedSaveRetainsIdentityAndEdits() = runTest(dispatcher) {
        val repo = Repository(); val model = vm(repo); runCurrent()
        model.manual(StationIdentity("1AT106", "JN44"), 27_265_000, Instant.EPOCH)
        model.edit { it.copy(peerCall = "26AT101", notes = "Portable") }; val id = model.uiState.value.draft!!.id
        model.save(); runCurrent(); assertTrue(repo.saves.isEmpty()); assertNotNull(model.uiState.value.error)
        repo.failSave = true; model.edit { it.copy(dialConfirmed = true) }; model.save(); runCurrent()
        assertEquals(id, model.uiState.value.draft?.id); assertEquals("Portable", model.uiState.value.draft?.notes)
        repo.failSave = false; model.save(); runCurrent()
        assertNull(model.uiState.value.draft); assertEquals(id, repo.snapshot.records.single().id)
        assertEquals(QsoOrigin.MANUAL, repo.snapshot.records.single().origin)
    }
    @Test fun rapidSaveAndCancelCannotInterruptAnAcceptedTransaction() = runTest(dispatcher) {
        val repo = Repository(); val model = vm(repo); runCurrent()
        model.manual(StationIdentity("1AT106", "JN44"), 27_265_000, Instant.EPOCH)
        model.edit { it.copy(peerCall = "26AT101", dialConfirmed = true) }; repo.saveGate = CompletableDeferred()
        model.save(); model.save(); model.cancel(); runCurrent()
        assertTrue(model.uiState.value.busy); assertNotNull(model.uiState.value.draft); assertEquals(1, repo.saves.size)
        repo.saveGate!!.complete(Unit); runCurrent()
        assertEquals(1, repo.snapshot.records.size); assertNull(model.uiState.value.draft)
    }
    @Test fun acceptedCompletionWriteSurvivesViewModelTeardown() = runTest(dispatcher) {
        val repo = Repository(); val model = vm(repo); runCurrent()
        model.completed(completion); stores.single().clear(); runCurrent()
        assertEquals(listOf(completion), repo.snapshot.pending)
    }
    @Test fun invalidOrFractionalFrequencyAndNonUtcDatesAreRejectedWithoutPersistence() {
        val valid = LogDraft.from("manual", details).copy(dialConfirmed = true)
        assertEquals(27_265_000L, valid.command().details.dialHz)
        for (bad in listOf(valid.copy(dialMhz = "27.2650001"), valid.copy(dialMhz = "NaN"), valid.copy(startUtc = "not UTC"),
            valid.copy(startUtc = "1970-01-01T01:00:15+01:00"), valid.copy(endUtc = "1970-01-01T00:00:00Z")))
            assertTrue(runCatching { bad.command() }.isFailure)
    }
}

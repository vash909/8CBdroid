package net.vash.ft8cbdroid.diagnostics

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.diagnostics.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Archive : DiagnosticsArchive {
        var saved: DiagnosticReport? = null
        var failRead = false; var failWrite = false; var failExport = false
        var exported: DiagnosticReport? = null
        var gate: CompletableDeferred<Unit>? = null
        var deleting = false
        override suspend fun read(): DiagnosticReport? { if (failRead) error("Broken file"); return saved }
        override suspend fun write(report: DiagnosticReport) {
            gate?.let { withContext(NonCancellable) { it.await() } }
            check(!deleting); if (failWrite) error("Full storage"); saved = report
        }
        override suspend fun delete() { deleting = true; saved = null }
        override suspend fun export(document: String, report: DiagnosticReport) { if (failExport) error("Provider refused"); exported = report }
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private fun TestScope.model(archive: Archive, observations: () -> DiagnosticObservation = { DiagnosticObservation() }) =
        DiagnosticsViewModel(archive, observations, { DiagnosticResources(pssKiB = 1024) }, { testScheduler.currentTime })
    @Test fun disabledUntilExplicitStartAndRecoveredCheckpointNeverResumes() = runTest(dispatcher) {
        val archive = Archive(); var reads = 0
        val vm = model(archive) { reads++; DiagnosticObservation() }; runCurrent()
        vm.sink.record(ReceiveDiagnosticEvent.DECODE_COMPLETE, 7); advanceTimeBy(90_000); runCurrent()
        assertNull(archive.saved); assertEquals(0, reads)
        vm.start(); runCurrent(); assertTrue(vm.uiState.value.active); assertEquals(1, reads)
        vm.stop(); advanceUntilIdle(); assertEquals(DiagnosticEnd.OPERATOR, archive.saved?.end)
        archive.saved = archive.saved!!.copy(end = DiagnosticEnd.RUNNING)
        val restored = model(archive); runCurrent()
        assertFalse(restored.uiState.value.active); assertEquals(DiagnosticEnd.INTERRUPTED, archive.saved?.end)
        assertFalse(restored.uiState.value.canStart)
    }
    @Test fun oneHourLimitAndLowRateSamplesReleaseSampler() = runTest(dispatcher) {
        val archive = Archive(); val vm = model(archive); runCurrent(); vm.start(); runCurrent()
        advanceTimeBy(3_600_000); runCurrent()
        assertFalse(vm.uiState.value.active); assertEquals(DiagnosticEnd.LIMIT, archive.saved?.end)
        assertEquals(121, archive.saved?.samples?.size)
        val frozen = archive.saved; advanceTimeBy(90_000); runCurrent(); assertEquals(frozen, archive.saved)
    }
    @Test fun deleteJoinsNonCancellableCheckpointAndCannotRecreateFile() = runTest(dispatcher) {
        val archive = Archive(); val vm = model(archive); runCurrent()
        archive.gate = CompletableDeferred(); vm.start(); runCurrent(); vm.delete(); runCurrent()
        assertTrue(vm.uiState.value.busy); assertFalse(archive.deleting)
        archive.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(archive.deleting); assertNull(archive.saved); assertNull(vm.uiState.value.report)
        assertTrue(vm.uiState.value.canStart)
        advanceTimeBy(90_000); runCurrent(); assertNull(archive.saved)
    }
    @Test fun frozenExportSnapshotCancelRetryAndDeleteInvalidatePicker() = runTest(dispatcher) {
        val archive = Archive(); val vm = model(archive); runCurrent(); vm.start(); runCurrent()
        assertTrue(vm.prepareExport()); vm.export(null); assertNull(archive.exported)
        vm.sink.record(ReceiveDiagnosticEvent.DECODE_COMPLETE, 12)
        assertTrue(vm.prepareExport()); vm.sink.record(ReceiveDiagnosticEvent.DECODE_COMPLETE, 15)
        archive.failExport = true; vm.export("first"); runCurrent(); assertNotNull(vm.uiState.value.error)
        archive.failExport = false; assertTrue(vm.prepareExport()); vm.export("second"); runCurrent()
        assertEquals(2L, archive.exported?.decodeCount)
        vm.sink.record(ReceiveDiagnosticEvent.DECODE_COMPLETE, 25)
        assertEquals(2L, archive.exported?.decodeCount)
        assertTrue(vm.prepareExport()); vm.delete(); advanceUntilIdle(); vm.export("late"); runCurrent()
        assertNull(vm.uiState.value.report); assertEquals(2L, archive.exported?.decodeCount)
    }
    @Test fun readFailureRequiresRetryOrExplicitDeletionAndWriteFailureStopsOnlyDiagnostics() = runTest(dispatcher) {
        val archive = Archive(); archive.failRead = true
        val vm = model(archive); runCurrent(); assertNotNull(vm.uiState.value.error); assertFalse(vm.uiState.value.canStart)
        archive.failRead = false; vm.load(); runCurrent(); assertTrue(vm.uiState.value.canStart)
        archive.failWrite = true; vm.start(); runCurrent()
        assertFalse(vm.uiState.value.active); assertNotNull(vm.uiState.value.error)
        assertEquals(DiagnosticEnd.OPERATOR, vm.uiState.value.report?.end)
        vm.delete(); advanceUntilIdle(); assertTrue(vm.uiState.value.canStart)
    }
}

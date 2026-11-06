package net.vash.ft8cbdroid.recording

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordedSlotViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pcm = Ft8PcmSlot.fromSamples(FloatArray(180000))
    private class Engine : Ft8Engine {
        var calls = 0
        var failure: Exception? = null
        var lastProfile: Ft8DecodeProfile? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile): Ft8DecodeResult {
            calls++
            lastProfile = profile
            failure?.let { throw it }
            gate?.let { withContext(NonCancellable) { it.await() } }
            return Ft8DecodeResult(emptyList(), 0, profile, 17, 0)
        }
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot = error("No TX")
    }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun model(engine: Engine = Engine(), reader: RecordedSlotReader = RecordedSlotReader { pcm }) =
        RecordedSlotViewModel(engine, reader, dispatcher)
    private fun RecordedSlotViewModel.select() {
        changeUtc("2026-11-06T12:00:00Z"); assertTrue(prepareImport()); importDocument("test:wave")
    }
    @Test fun invalidUtcAndCancelledPickerDoNotReadOrDecode() = runTest(dispatcher) {
        val engine = Engine(); var reads = 0
        val vm = model(engine, RecordedSlotReader { reads++; pcm })
        assertFalse(vm.prepareImport()); assertNotNull(vm.uiState.value.error)
        vm.changeUtc("2026-11-06T12:00:15Z")
        assertTrue(vm.prepareImport()); assertFalse(vm.prepareImport()); vm.importDocument(null)
        assertTrue(vm.uiState.value.canImport); assertNull(vm.uiState.value.result)
        assertEquals(0, reads); assertEquals(0, engine.calls)
    }
    @Test fun onlyOneRequestRunsAndChangingUtcDiscardsResults() = runTest(dispatcher) {
        val engine = Engine().apply { gate = CompletableDeferred() }; val vm = model(engine)
        vm.select(); runCurrent(); assertFalse(vm.prepareImport()); assertEquals(1, engine.calls)
        engine.gate!!.complete(Unit); advanceUntilIdle()
        assertNotNull(vm.uiState.value.result); assertTrue(vm.uiState.value.result!!.decodes.isEmpty())
        vm.changeUtc("2026-11-06T12:00:30Z"); assertNull(vm.uiState.value.result)
    }
    @Test fun cancelOrStationChangeDiscardsLateNativeResults() = runTest(dispatcher) {
        for (stationChange in listOf(false, true)) {
            val engine = Engine().apply { gate = CompletableDeferred() }; val vm = model(engine)
            vm.select(); runCurrent()
            if (stationChange) vm.changeStation("1AT106") else vm.cancel()
            engine.gate!!.complete(Unit); advanceUntilIdle()
            assertNull(vm.uiState.value.result); assertFalse(vm.uiState.value.isDecoding)
            assertTrue(vm.uiState.value.canImport)
        }
    }
    @Test fun failedReadCanRetryAndOldFailureCannotReplaceNewResult() = runTest(dispatcher) {
        var fail = true
        val vm = model(reader = RecordedSlotReader { if (fail) throw IOException("provider") else pcm })
        vm.select(); advanceUntilIdle(); assertNotNull(vm.uiState.value.error); assertTrue(vm.uiState.value.canImport)
        fail = false; vm.select(); advanceUntilIdle(); assertNull(vm.uiState.value.error); assertNotNull(vm.uiState.value.result)
        vm.clear(); assertNull(vm.uiState.value.result)
    }
    @Test fun replacingCancelledRequestCannotPublishOrClearNewRequest() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>(); val engine = Engine().apply { gate = old }; val vm = model(engine)
        vm.select(); runCurrent(); vm.cancel(); engine.gate = null
        vm.changeUtc("2026-11-06T12:00:15Z"); assertTrue(vm.prepareImport()); vm.importDocument("new:wave"); advanceUntilIdle()
        assertEquals("2026-11-06T12:00:15Z", vm.uiState.value.result!!.context.slotStartUtc.toString())
        old.complete(Unit); advanceUntilIdle()
        assertEquals("2026-11-06T12:00:15Z", vm.uiState.value.result!!.context.slotStartUtc.toString())
    }
    @Test fun profilesAreSnapshotsAndChangesDiscardOldResultsAndPendingSelection() = runTest(dispatcher) {
        val engine = Engine(); val vm = model(engine)
        vm.changeProfile(Ft8DecodeProfile.FAST); vm.select(); advanceUntilIdle()
        assertEquals(Ft8DecodeProfile.FAST, engine.lastProfile)
        assertEquals(Ft8DecodeProfile.FAST, vm.uiState.value.result!!.profile)
        vm.changeProfile(Ft8DecodeProfile.STANDARD); assertNull(vm.uiState.value.result)
        assertTrue(vm.prepareImport()); vm.changeProfile(Ft8DecodeProfile.FAST)
        vm.importDocument("stale:wave"); advanceUntilIdle(); assertEquals(1, engine.calls)
    }
    @Test fun busyAndDeadlineErrorsAreVisibleAndRetryDoesNotKeepStaleResults() = runTest(dispatcher) {
        val engine = Engine(); val vm = model(engine)
        for (failure in listOf(Ft8EngineBusyException(), Ft8EngineTimeoutException("Decode deadline exceeded."))) {
            engine.failure = failure; vm.select(); advanceUntilIdle()
            assertEquals(failure.message, vm.uiState.value.error); assertNull(vm.uiState.value.result)
            assertTrue(vm.uiState.value.canImport)
        }
        engine.failure = null; vm.select(); advanceUntilIdle(); assertNotNull(vm.uiState.value.result)
    }

}

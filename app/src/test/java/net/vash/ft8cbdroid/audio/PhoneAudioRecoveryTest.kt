package net.vash.ft8cbdroid.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.time.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAudioRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private class Capture : TimestampedMicrophoneCapture {
        override val inputs = MutableStateFlow(listOf(AudioInput(1, "Phone", AudioInputKind.PHONE), AudioInput(2, "USB", AudioInputKind.USB)))
        var starts = 0; var active = 0; var cleanups = 0
        override suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit) {
            starts++; active++
            try {
                update(CaptureMetrics(CaptureRoute(inputs.value.first { it.id == inputId }, 48000, "mono", "test", "test", 4800), 4800, 1200, -10.0, -15.0, 0, 1200))
                awaitCancellation()
            } finally { withContext(NonCancellable) { delay(100); active--; cleanups++ } }
        }
        override suspend fun captureTimed(inputId: Int, update: suspend (CaptureMetrics) -> Unit, chunk: (TimedCaptureChunk) -> Unit) = capture(inputId, update)
        override fun snapshot() = FloatArray(100)
        override fun clear() {}
        override fun close() {}
    }
    private fun coordinator(vm: CaptureViewModel, permission: () -> Boolean = { true }) = PhoneAudioCoordinator(object : PhoneAudioReceiver {
        override fun blockStarts(blocked: Boolean) = vm.blockForOutput(blocked)
        override suspend fun pause() = vm.pauseForOutput(permission)
    })
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun actualViewModelJoinsInputCleanupBlocksStartsAndResumesOnlyOnceAfterSuccess() = runTest(dispatcher) {
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.requestStart(true); runCurrent(); assertEquals(1, capture.active)
        val coordinator = coordinator(vm)
        val run = launch { assertTrue(coordinator.output(true) {
            assertEquals(0, capture.active); assertEquals(1, capture.cleanups)
            assertNull(vm.uiState.value.metrics); assertNull(vm.uiState.value.latestSlotUtcMillis)
            assertTrue(vm.uiState.value.outputBusy); vm.requestStart(true); assertEquals(1, capture.starts)
        }) }
        run.join(); runCurrent(); assertEquals(2, capture.starts); assertEquals(1, capture.active)
        assertFalse(vm.uiState.value.outputBusy)
        vm.stop(clear = true); advanceUntilIdle()
    }
    @Test fun stopStationPermissionAndInputInventoryChangesInvalidateRecovery() = runTest(dispatcher) {
        for (change in listOf<(CaptureViewModel, Capture) -> Unit>(
            { vm, _ -> vm.stop() }, { vm, _ -> vm.changeStation("1AT106") },
            { _, capture -> capture.inputs.value = listOf(AudioInput(2, "USB", AudioInputKind.USB)) },
            { _, capture -> capture.inputs.value = capture.inputs.value + AudioInput(3, "Other", AudioInputKind.PHONE) })) {
            val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
            vm.requestStart(true); runCurrent()
            assertFalse(coordinator(vm).output(true) { change(vm, capture); yield() })
            runCurrent(); assertEquals(1, capture.starts); assertEquals(0, capture.active)
        }
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.requestStart(true); runCurrent()
        assertFalse(coordinator(vm) { false }.output(true) {})
        assertEquals(1, capture.starts)
    }
    @Test fun inactiveUsbAndPendingPermissionInputsNeverAcquireAutomaticRecovery() = runTest(dispatcher) {
        for (mode in 0..2) {
            val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
            if (mode == 1) { vm.select(2); vm.requestStart(true); runCurrent() }
            if (mode == 2) vm.requestStart(false)
            val before = capture.starts
            assertFalse(coordinator(vm).output(true) {})
            vm.permissionResult(true); runCurrent()
            assertEquals(before, capture.starts); assertEquals(0, capture.active)
        }
    }
    @Test fun expiredOrChangedCorrectionBlocksRecoveryAndClockUncertaintyAgeAloneDoesNot() = runTest(dispatcher) {
        for (changeClock in 0..2) {
            var reading = ClockReading(10_000_000_000, 1_800_000_000_000)
            val clock = ReceiveClock(TimeSource { reading })
            clock.apply(TimeMeasurement("NTP/test", reading.monotonicNanos, reading.unixMillis * 1_000_000, 2.0, reading))
            val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, clock)
            runCurrent(); vm.assembleUtc(true); vm.requestStart(true); runCurrent()
            vm.blockForOutput(true)
            val recovery = vm.pauseForOutput { true }
            vm.blockForOutput(false)
            assertNotNull(recovery)
            if (changeClock == 0) reading = ClockReading(611_000_000_000, 1_800_000_601_000)
            if (changeClock == 1) clock.reset()
            if (changeClock == 2) reading = ClockReading(11_000_000_000, 1_800_000_001_000)
            assertEquals(changeClock == 2, recovery!!())
            runCurrent(); assertFalse(recovery())
            vm.stop(); advanceUntilIdle()
        }
    }
}

package net.vash.ft8cbdroid.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.audio.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Capture : MicrophoneCapture {
        override val inputs = MutableStateFlow(listOf(AudioInput(1, "Built-in microphone"), AudioInput(2, "Test USB input")))
        var starts = 0; var active = 0; var cleared = 0
        var fail: CompletableDeferred<Unit>? = null
        var cleanup: CompletableDeferred<Unit>? = null
        var pcm = floatArrayOf(.25f, -.25f)
        var deliver: (suspend (CaptureMetrics) -> Unit)? = null
        val metrics = CaptureMetrics(CaptureRoute(inputs.value[0], 48000, "48000 Hz", "Unprocessed", "AGC disabled", 9600),
            4800, 1200, -12.0, -15.0, 0, 2)
        override suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit) {
            starts++; active++; deliver = update
            try {
                update(metrics)
                fail?.let { it.await(); throw CaptureException("Audio route lost. Reconnect and retry.") }
                awaitCancellation()
            } finally { withContext(NonCancellable) { cleanup?.await() }; active-- }
        }
        override fun snapshot() = pcm.copyOf()
        override fun clear() { cleared++ }
        override fun close() = Unit
    }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    @Test fun deniedPermissionDoesNotOpenHardwareAndExplicitRetryWorks() = runTest(dispatcher) {
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        assertTrue(vm.requestStart(false)); vm.permissionResult(false); runCurrent()
        assertEquals(0, capture.starts); assertNotNull(vm.uiState.value.error)
        assertTrue(vm.requestStart(false)); vm.permissionResult(true); runCurrent()
        assertEquals(CapturePhase.CAPTURING, vm.uiState.value.phase)
        vm.stop(); advanceUntilIdle(); assertEquals(0, capture.active)
    }
    @Test fun delayedPermissionGrantAfterBackgroundNeverStartsMicrophone() = runTest(dispatcher) {
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        assertTrue(vm.requestStart(false)); vm.stop(clear = true); vm.permissionResult(true); advanceUntilIdle()
        assertEquals(0, capture.starts); assertFalse(vm.uiState.value.awaitingPermission)
    }
    @Test fun immediateStopBeforeWorkerStartsStillAllowsRetry() = runTest(dispatcher) {
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.requestStart(true); vm.stop(clear = true); advanceUntilIdle()
        assertEquals(0, capture.starts); assertEquals(CapturePhase.IDLE, vm.uiState.value.phase)
        vm.requestStart(true); runCurrent(); assertEquals(1, capture.starts)
        vm.stop(); advanceUntilIdle()
    }
    @Test fun stopWaitsForCleanupRejectsRestartAndIgnoresLateLevels() = runTest(dispatcher) {
        val capture = Capture().apply { cleanup = CompletableDeferred() }
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.requestStart(true); runCurrent(); vm.stop(clear = true); runCurrent()
        assertEquals(CapturePhase.STOPPING, vm.uiState.value.phase)
        vm.requestStart(true); capture.deliver!!(capture.metrics); runCurrent()
        assertEquals(1, capture.starts); assertEquals(CapturePhase.STOPPING, vm.uiState.value.phase)
        capture.cleanup!!.complete(Unit); advanceUntilIdle()
        assertNull(vm.uiState.value.metrics); assertEquals(CapturePhase.IDLE, vm.uiState.value.phase)
        vm.requestStart(true); runCurrent(); assertEquals(2, capture.starts)
        vm.stop(); advanceUntilIdle()
    }
    @Test fun routeLossClearsRecordingAndRequiresExplicitRestartWithAnAvailableDevice() = runTest(dispatcher) {
        val capture = Capture().apply { fail = CompletableDeferred() }; val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.select(2); vm.requestStart(true); runCurrent()
        capture.inputs.value = listOf(capture.inputs.value.first()); capture.fail!!.complete(Unit); advanceUntilIdle()
        assertNotNull(vm.uiState.value.error); assertNull(vm.uiState.value.metrics); assertFalse(vm.uiState.value.canStart)
        assertEquals(0, capture.active); capture.fail = null
        vm.select(1); vm.requestStart(true); runCurrent(); assertEquals(2, capture.starts)
        vm.stop(); advanceUntilIdle()
    }
    @Test fun exportUsesStoppedSnapshotAndCancelledPickerDoesNotWrite() = runTest(dispatcher) {
        val capture = Capture(); val exports = mutableListOf<ByteArray>()
        val vm = CaptureViewModel(capture, CaptureWriter { _, bytes -> exports += bytes }); runCurrent()
        vm.requestStart(true); runCurrent(); assertFalse(vm.prepareExport())
        vm.stop(); advanceUntilIdle(); assertTrue(vm.prepareExport()); vm.export(null); assertTrue(exports.isEmpty())
        assertTrue(vm.prepareExport()); capture.pcm.fill(0f); vm.export("test:output"); advanceUntilIdle()
        assertArrayEquals(CaptureWav.encode(floatArrayOf(.25f, -.25f)), exports.single())
        val fresh = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        assertNull(fresh.uiState.value.metrics); assertEquals(1, capture.starts)
    }
}

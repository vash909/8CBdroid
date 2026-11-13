package net.vash.ft8cbdroid.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    @Test fun hardwarePublicationDoesNotWaitForMainAndStopRetainsTheLastObservation() = runTest(dispatcher) {
        val start = java.util.concurrent.CountDownLatch(1)
        val emitted = java.util.concurrent.CountDownLatch(1)
        val cleaned = java.util.concurrent.CountDownLatch(1)
        val base = Capture()
        val capture = object : MicrophoneCapture by base {
            override suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit) = withContext(Dispatchers.Default) {
                try {
                    check(start.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    repeat(100) { update(base.metrics.copy(inputSamples = it + 1L)) }
                    emitted.countDown(); awaitCancellation()
                } finally { cleaned.countDown() }
            }
        }
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.requestStart(true); runCurrent(); start.countDown()
        // Deliberately keep the main test dispatcher busy while the hardware publishes.
        assertTrue(emitted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        vm.stop(); assertTrue(cleaned.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // Hardware cleanup precedes dispatching completion back to Main; await both.
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { vm.uiState.first { it.phase == CapturePhase.IDLE } }
        }
        assertEquals(100L, vm.uiState.value.metrics!!.inputSamples)
        assertEquals(CapturePhase.IDLE, vm.uiState.value.phase)
    }
    @Test fun serviceGateMustAcceptBeforeAnyHardwareOrWorkerIsOpened() = runTest(dispatcher) {
        val capture = Capture()
        var promoted = false; var requested = 0
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, startSession = { requested++; promoted })
        runCurrent()
        vm.requestStart(true); runCurrent()
        assertEquals(1, requested); assertEquals(0, capture.starts)
        assertEquals(CapturePhase.IDLE, vm.uiState.value.phase)
        promoted = true
        vm.requestStart(true); runCurrent()
        assertEquals(1, capture.starts); assertEquals(1, capture.active)
        vm.requestStart(true); runCurrent()
        assertEquals(1, capture.starts)
        vm.stop(clear = true); advanceUntilIdle()
        assertEquals(0, capture.active)
    }
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
    @Test fun applyingAProfileJoinsHardwareCleanupAndInvalidatesLatePermissionCallbacks() = runTest(dispatcher) {
        val capture = Capture().apply { cleanup = CompletableDeferred() }
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.requestStart(true); runCurrent()
        val stopped = launch { vm.stopForProfile() }; runCurrent()
        assertFalse(stopped.isCompleted); assertEquals(1, capture.active)
        capture.cleanup!!.complete(Unit); runCurrent(); stopped.join()
        assertEquals(0, capture.active); assertEquals(CapturePhase.IDLE, vm.uiState.value.phase)
        assertNull(vm.uiState.value.metrics)
        vm.requestStart(false); vm.stopForProfile(); vm.permissionResult(true); runCurrent()
        assertEquals(1, capture.starts)
    }
    @Test fun savedUsbPreferenceNeverFallsBackToPhoneAndDoesNotPersistDeviceIds() = runTest(dispatcher) {
        val capture = Capture()
        capture.inputs.value = listOf(AudioInput(71, "Phone", AudioInputKind.PHONE))
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }); runCurrent()
        vm.stopForProfile()
        vm.configureProfile(net.vash.ft8cbdroid.core.profile.OperatingProfile(audioInput = net.vash.ft8cbdroid.core.profile.ProfileAudioRoute.USB))
        assertNull(vm.uiState.value.selectedId); assertFalse(vm.uiState.value.canStart)
        capture.inputs.value += AudioInput(102, "USB", AudioInputKind.USB); runCurrent()
        assertEquals(102, vm.uiState.value.selectedId); assertEquals(0, capture.starts)
        capture.inputs.value = listOf(capture.inputs.value.first()); runCurrent()
        assertNull(vm.uiState.value.selectedId); assertFalse(vm.uiState.value.canStart)
        capture.inputs.value += AudioInput(205, "USB reattached", AudioInputKind.USB); runCurrent()
        assertEquals(205, vm.uiState.value.selectedId); assertEquals(0, capture.starts)
        vm.configureProfile(net.vash.ft8cbdroid.core.profile.OperatingProfile())
        assertEquals(71, vm.uiState.value.selectedId)
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

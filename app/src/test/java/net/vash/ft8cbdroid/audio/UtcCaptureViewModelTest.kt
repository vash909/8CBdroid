package net.vash.ft8cbdroid.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.time.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UtcCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Source(var now: ClockReading = ClockReading(10_000_000_000, 1_800_000_000_000)) : TimeSource { override fun read() = now }
    private class Capture : TimestampedMicrophoneCapture {
        override val inputs = MutableStateFlow(listOf(AudioInput(1, "Test microphone"), AudioInput(2, "Other input")))
        var starts = 0
        val metrics = CaptureMetrics(CaptureRoute(inputs.value[0], 48000, "mono", "test", "test", 9600), 0, 0, -10.0, -12.0, 0, 10)
        lateinit var deliver: (TimedCaptureChunk) -> Unit
        lateinit var update: suspend (CaptureMetrics) -> Unit
        override suspend fun captureTimed(inputId: Int, update: suspend (CaptureMetrics) -> Unit, chunk: (TimedCaptureChunk) -> Unit) {
            starts++; deliver = chunk; this.update = update; update(metrics); awaitCancellation()
        }
        override suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit) { starts++; awaitCancellation() }
        override fun snapshot() = FloatArray(10)
        override fun clear() = Unit
        override fun close() = Unit
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun utcModeDoesNotOpenMicrophoneWithoutSynchronization() = runTest(dispatcher) {
        val source = Source(); val capture = Capture()
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, ReceiveClock(source)); runCurrent()
        vm.assembleUtc(true); vm.requestStart(true); runCurrent()
        assertEquals(0, capture.starts); assertEquals(CapturePhase.IDLE, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.error!!.contains("Synchronize"))
    }
    @Test fun onlyACompleteStoppedSlotCanBeExportedAndPickerRetryPreservesTheSnapshot() = runTest(dispatcher) {
        val source = Source(); val clock = ReceiveClock(source)
        clock.apply(TimeMeasurement("GPS/GNSS", source.now.monotonicNanos, source.now.unixMillis * 1_000_000, 1.0, source.now))
        val capture = Capture(); val writes = mutableListOf<ByteArray>()
        val vm = CaptureViewModel(capture, CaptureWriter { _, bytes -> writes += bytes }, clock); runCurrent()
        vm.assembleUtc(true); vm.requestStart(true); runCurrent()
        for (second in 0 until 15) {
            source.now = ClockReading(10_000_000_000 + second * 1_000_000_000L, 1_800_000_000_000 + second * 1000L)
            capture.deliver(TimedCaptureChunk(second * 12000L, source.now.monotonicNanos, FloatArray(12000) { .25f }, 0.0))
            capture.update(capture.metrics)
        }
        assertFalse(vm.prepareSlotExport()); vm.stop(); advanceUntilIdle()
        // A backend's late callback must not replace the stopped slot under its old UTC label.
        repeat(15) { second -> capture.deliver(TimedCaptureChunk((second + 15) * 12000L,
            25_000_000_000L + second * 1_000_000_000L, FloatArray(12000) { .5f }, 0.0)) }
        assertTrue(vm.prepareSlotExport()); assertTrue(vm.isChoosingDocument); vm.export(null)
        assertTrue(vm.uiState.value.canExportSlot); assertTrue(writes.isEmpty())
        assertTrue(vm.prepareSlotExport()); vm.export("test:slot"); advanceUntilIdle()
        assertEquals(360044, writes.single().size)
        assertEquals(8192.toShort(), java.nio.ByteBuffer.wrap(writes.single()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(44))
        vm.select(2); assertFalse(vm.uiState.value.canExportSlot)
        vm.stop(clear = true); assertNull(vm.uiState.value.latestSlotUtcMillis)
    }
}

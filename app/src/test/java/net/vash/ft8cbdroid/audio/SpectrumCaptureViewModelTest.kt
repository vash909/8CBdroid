package net.vash.ft8cbdroid.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.audio.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpectrumCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Capture : PcmMicrophoneCapture {
        override val inputs = MutableStateFlow(listOf(AudioInput(1, "Test microphone"), AudioInput(2, "Other input")))
        lateinit var feed: (FloatArray) -> Unit
        override suspend fun capturePcm(inputId: Int, update: suspend (CaptureMetrics) -> Unit,
            pcm: (FloatArray) -> Unit, timed: ((TimedCaptureChunk) -> Unit)?) {
            feed = pcm; update(CaptureMetrics(CaptureRoute(inputs.value[0], 48000, "mono", "test", "test", 9600),
                48000, 12000, -12.0, -15.0, 0, 12000)); awaitCancellation()
        }
        override suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit) = error("Expected PCM tap")
        override fun snapshot() = FloatArray(0)
        override fun clear() = Unit
        override fun close() = Unit
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun spectrumNeedsNoTimeSyncAndStopClearsHistoryButPreservesTemporaryCursors() = runTest(dispatcher) {
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, worker = dispatcher); runCurrent()
        vm.spectrumView(SpectrumView().copy(cursor = OffsetCursor.TX).select(1800f).zoom(750f))
        vm.requestStart(true); runCurrent(); capture.feed(FloatArray(4096)); runCurrent()
        assertEquals(1, vm.uiState.value.spectrum.processed); assertFalse(vm.uiState.value.assembleUtc)
        vm.stop(); advanceUntilIdle(); assertTrue(vm.uiState.value.spectrum.frames.isEmpty())
        assertEquals(1800, vm.uiState.value.spectrumView.txHz)
        capture.feed(FloatArray(12000)); runCurrent(); assertTrue(vm.uiState.value.spectrum.frames.isEmpty())
        vm.requestStart(true); runCurrent(); capture.feed(FloatArray(4096)); runCurrent()
        assertEquals(1, vm.uiState.value.spectrum.processed)
        vm.stop(clear = true); advanceUntilIdle()
    }
    @Test fun stationInputAndNewViewModelDoNotKeepAnOldWaterfall() = runTest(dispatcher) {
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, worker = dispatcher); runCurrent()
        vm.requestStart(true); runCurrent(); capture.feed(FloatArray(4096)); runCurrent()
        vm.changeStation("26AT101"); advanceUntilIdle(); assertTrue(vm.uiState.value.spectrum.frames.isEmpty())
        vm.select(2); assertTrue(vm.uiState.value.spectrum.frames.isEmpty())
        val fresh = CaptureViewModel(Capture(), CaptureWriter { _, _ -> }, worker = dispatcher); runCurrent()
        assertEquals(SpectrumView(), fresh.uiState.value.spectrumView); assertTrue(fresh.uiState.value.spectrum.frames.isEmpty())
    }
    @Test fun fastObservationsDoNotInvalidateControlsButStopEnablesExport() = runTest(dispatcher) {
        val capture = Capture(); val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, worker = dispatcher); runCurrent()
        val controls = mutableListOf<CaptureUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.controls.collect { controls += it } }
        vm.requestStart(true); runCurrent(); val active = controls.size
        repeat(10) { capture.feed(FloatArray(2400)); runCurrent() }
        assertTrue(vm.uiState.value.spectrum.processed > 0)
        assertEquals(active, controls.size); assertFalse(controls.last().canExport)
        vm.stop(); advanceUntilIdle()
        assertEquals(CapturePhase.IDLE, controls.last().phase); assertTrue(controls.last().canExport)
    }

}

package net.vash.ft8cbdroid.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.time.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Source(var now: ClockReading = ClockReading(10_000_000_000, 1_800_000_000_000)) : TimeSource { override fun read() = now }
    private class Capture : TimestampedMicrophoneCapture {
        override val inputs = MutableStateFlow(listOf(AudioInput(1, "Test microphone"), AudioInput(2, "Other input")))
        var starts = 0
        lateinit var deliver: (TimedCaptureChunk) -> Unit
        val metrics = CaptureMetrics(CaptureRoute(inputs.value[0], 48000, "mono", "test", "test", 9600), 48000, 12000, -12.0, -15.0, 0, 12000)
        override suspend fun captureTimed(inputId: Int, update: suspend (CaptureMetrics) -> Unit, chunk: (TimedCaptureChunk) -> Unit) {
            starts++; deliver = chunk; update(metrics); awaitCancellation()
        }
        override suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit): Unit = error("Expected timestamped capture")
        override fun snapshot() = FloatArray(10)
        override fun clear() = Unit
        override fun close() = Unit
        fun complete(source: Source, startSecond: Int) {
            repeat(15) { second ->
                source.now = ClockReading(10_000_000_000 + (startSecond + second) * 1_000_000_000L, 1_800_000_000_000 + (startSecond + second) * 1000L)
                deliver(TimedCaptureChunk(second * 12000L, source.now.monotonicNanos, FloatArray(12000) { .25f }, 0.0))
            }
        }
    }
    private class Engine : Ft8Engine {
        var calls = 0
        var profile: Ft8DecodeProfile? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile): Ft8DecodeResult {
            calls++; this.profile = profile
            gate?.let { withContext(NonCancellable) { it.await() } }
            val payload = (CbMessageCodec.pack("CQ 1AT106") as PackResult.Encoded).payload
            return Ft8DecodeResult(listOf(Ft8DecodedFrame(payload, .8f, 1000f, 30)), 1, profile)
        }
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot = error("No TX")
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private fun clock(source: Source) = ReceiveClock(source).also {
        it.apply(TimeMeasurement("NTP: test", source.now.monotonicNanos, source.now.unixMillis * 1_000_000, 2.0, source.now))
    }
    @Test fun liveModeRequiresMeasuredTimeBeforeOpeningHardware() = runTest(dispatcher) {
        val source = Source(); val capture = Capture(); val engine = Engine()
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, ReceiveClock(source), engine, dispatcher); runCurrent()
        vm.decodeLive(true); assertTrue(vm.uiState.value.assembleUtc)
        vm.requestStart(true); runCurrent()
        assertEquals(0, capture.starts); assertEquals(0, engine.calls); assertNotNull(vm.uiState.value.error)
        vm.assembleUtc(false); assertFalse(vm.uiState.value.decodeLive)
    }
    @Test fun invalidStationFailsVisiblyBeforeLaunchingCaptureOrDecoderWork() = runTest(dispatcher) {
        val source = Source(); val capture = Capture(); val engine = Engine()
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, clock(source), engine, dispatcher); runCurrent()
        vm.changeStation("INVALID"); vm.decodeLive(true); vm.requestStart(true); runCurrent()
        assertEquals(CapturePhase.IDLE, vm.uiState.value.phase); assertNotNull(vm.uiState.value.error)
        assertEquals(0, capture.starts); assertEquals(0, engine.calls)
    }
    @Test fun completeCapturedSlotsDecodeAutomaticallyWithTheSelectedProfileAndStopClearsResults() = runTest(dispatcher) {
        val source = Source(); val capture = Capture(); val engine = Engine()
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, clock(source), engine, dispatcher); runCurrent()
        vm.changeStation(""); vm.decodeLive(true); vm.liveProfile(Ft8DecodeProfile.FAST)
        vm.requestStart(true); runCurrent(); capture.complete(source, 0); runCurrent()
        assertEquals(Ft8DecodeProfile.FAST, engine.profile)
        assertEquals("CQ 1AT106", (vm.uiState.value.live.rows.single().decode.message as UnpackResult.Decoded).message.text)
        assertEquals(1, vm.uiState.value.live.decodedSlots)
        vm.stop(); assertTrue(vm.uiState.value.live.rows.isEmpty()); advanceUntilIdle()
        assertEquals(CapturePhase.IDLE, vm.uiState.value.phase); assertTrue(vm.uiState.value.canExportSlot)
        assertEquals(0, vm.uiState.value.live.learnedCalls)
    }
    @Test fun stopWaitsForDecoderCleanupAndLateFramesCannotReappearAfterRestart() = runTest(dispatcher) {
        val source = Source(); val capture = Capture(); val engine = Engine().apply { gate = CompletableDeferred() }
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, clock(source), engine, dispatcher); runCurrent()
        vm.decodeLive(true); vm.requestStart(true); runCurrent(); capture.complete(source, 0); runCurrent()
        vm.stop(clear = true); vm.requestStart(true); runCurrent()
        assertEquals(CapturePhase.STOPPING, vm.uiState.value.phase); assertEquals(1, capture.starts)
        engine.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(vm.uiState.value.live.rows.isEmpty()); assertNull(vm.uiState.value.latestSlotUtcMillis)
        engine.gate = null; vm.requestStart(true); runCurrent(); capture.complete(source, 15); runCurrent()
        assertEquals(2, capture.starts); assertEquals(1, vm.uiState.value.live.rows.size)
        assertEquals(1_800_000_015_000L, vm.uiState.value.live.rows.single().decode.slotStartUtc.toEpochMilli())
        vm.stop(clear = true); advanceUntilIdle()
    }
    @Test fun stationChangeEndsTheRunAndInputOrModeChangesCannotKeepItsResults() = runTest(dispatcher) {
        val source = Source(); val capture = Capture(); val engine = Engine()
        val vm = CaptureViewModel(capture, CaptureWriter { _, _ -> }, clock(source), engine, dispatcher); runCurrent()
        vm.decodeLive(true); vm.requestStart(true); runCurrent(); capture.complete(source, 0); runCurrent()
        vm.changeStation("26AT101"); advanceUntilIdle()
        assertEquals(CapturePhase.IDLE, vm.uiState.value.phase); assertTrue(vm.uiState.value.live.rows.isEmpty())
        assertNull(vm.uiState.value.latestSlotUtcMillis)
        vm.select(2); vm.decodeLive(false); assertTrue(vm.uiState.value.live.rows.isEmpty())
    }
}

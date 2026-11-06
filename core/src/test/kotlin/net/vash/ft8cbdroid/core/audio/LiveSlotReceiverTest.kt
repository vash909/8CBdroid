package net.vash.ft8cbdroid.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.recording.RecordedDecodeFlag
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSlotReceiverTest {
    private val pcm = Ft8PcmSlot.fromSamples(FloatArray(180000))
    private fun slot(index: Int) = CompletedUtcSlot(1_800_000_000_000L + index * 15000L, pcm, "NTP: test", 12.0)
    private fun frame(text: String) = Ft8DecodedFrame((CbMessageCodec.pack(text) as PackResult.Encoded).payload, .8f, 1000f, 30, -8f)
    private class Engine : Ft8Engine {
        val calls = mutableListOf<Ft8DecodeProfile>()
        var frames = emptyList<Ft8DecodedFrame>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile): Ft8DecodeResult {
            calls += profile
            gate?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }
            return Ft8DecodeResult(frames, frames.size, profile, 21)
        }
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot = error("No TX")
    }
    @Test fun overloadedAudioNeverWaitsAndOnlyTheLatestPendingSlotRuns() = runTest {
        val engine = Engine().apply { gate = CompletableDeferred(); frames = listOf(frame("CQ 1AT106")) }
        val receiver = LiveSlotReceiver(engine, backgroundScope, null, Ft8DecodeProfile.FAST, StandardTestDispatcher(testScheduler))
        receiver.offer(slot(0)); runCurrent()
        for (i in 1..5) receiver.offer(slot(i))
        assertEquals(1, engine.calls.size); assertEquals(4, receiver.uiState.value.skippedSlots)
        engine.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf(Ft8DecodeProfile.FAST, Ft8DecodeProfile.FAST), engine.calls)
        assertEquals(2, receiver.uiState.value.decodedSlots)
        assertEquals(listOf(slot(5).utcStartMillis, slot(0).utcStartMillis), receiver.uiState.value.rows.map { it.decode.slotStartUtc.toEpochMilli() })
        receiver.cancel()
    }
    @Test fun hashesAreCommittedOnlyBetweenCompleteSlotsAndNeverSharedWithAnotherRun() = runTest {
        val engine = Engine().apply { frames = listOf(frame("CQ 1AT106"), frame("<1AT106> 26AT101")) }
        val worker = StandardTestDispatcher(testScheduler)
        val receiver = LiveSlotReceiver(engine, backgroundScope, null, Ft8DecodeProfile.STANDARD, worker)
        receiver.offer(slot(0)); runCurrent()
        assertTrue(RecordedDecodeFlag.UNKNOWN_HASH in receiver.uiState.value.rows[1].decode.flags)
        engine.frames = listOf(frame("<1AT106> 26AT101")); receiver.offer(slot(1)); runCurrent()
        assertEquals("<1AT106> 26AT101", (receiver.uiState.value.rows.first().decode.message as UnpackResult.Decoded).message.text)
        val cold = LiveSlotReceiver(engine, backgroundScope, null, Ft8DecodeProfile.STANDARD, worker)
        cold.offer(slot(2)); runCurrent()
        assertTrue(RecordedDecodeFlag.UNKNOWN_HASH in cold.uiState.value.rows.single().decode.flags)
        receiver.cancel(); cold.cancel()
    }
    @Test fun localStationOrientationAndRowMetadataPreserveCbInterpretation() = runTest {
        val engine = Engine().apply { frames = listOf(frame("<1AT106> 26AT101")) }
        val receiver = LiveSlotReceiver(engine, backgroundScope, "1AT106", Ft8DecodeProfile.STANDARD, StandardTestDispatcher(testScheduler))
        receiver.offer(slot(0).copy(source = "GPS/GNSS", uncertaintyMillis = null)); runCurrent()
        val row = receiver.uiState.value.rows.single()
        assertEquals("<1AT106> 26AT101", (row.decode.message as UnpackResult.Decoded).message.text)
        assertEquals(.3f, row.decode.deltaTimeSeconds, .0001f)
        assertEquals("GPS/GNSS", row.timeSource); assertNull(row.uncertaintyMillis)
        assertTrue(RecordedDecodeFlag.SNR_ESTIMATED in row.decode.flags)
        receiver.cancel()
    }
    @Test fun busyAndDeadlineFailuresSkipOneSlotAndTheNextSlotCanRecover() = runTest {
        for (failure in listOf(Ft8EngineBusyException(), Ft8EngineTimeoutException("Deadline expired"))) {
            val engine = Engine().apply { this.failure = failure }
            val receiver = LiveSlotReceiver(engine, backgroundScope, null, Ft8DecodeProfile.STANDARD, StandardTestDispatcher(testScheduler))
            receiver.offer(slot(0)); runCurrent()
            assertEquals(failure.message, receiver.uiState.value.error); assertEquals(1, receiver.uiState.value.failedSlots)
            engine.failure = null; receiver.offer(slot(1)); runCurrent()
            assertNull(receiver.uiState.value.error); assertEquals(1, receiver.uiState.value.decodedSlots)
            receiver.cancel()
        }
    }
    @Test fun cancellationRejectsLateFramesAndDropsQueuedAudio() = runTest {
        val engine = Engine().apply { gate = CompletableDeferred(); frames = listOf(frame("CQ 1AT106")) }
        val receiver = LiveSlotReceiver(engine, backgroundScope, null, Ft8DecodeProfile.STANDARD, StandardTestDispatcher(testScheduler))
        receiver.offer(slot(0)); runCurrent(); receiver.offer(slot(1)); receiver.cancel()
        engine.gate!!.complete(Unit); runCurrent()
        receiver.offer(slot(2)); runCurrent()
        assertEquals(1, engine.calls.size); assertTrue(receiver.uiState.value.rows.isEmpty())
    }
    @Test fun historyIsBoundedAndEmptySlotsStillAdvanceTheReceiveSummary() = runTest {
        val engine = Engine().apply { frames = listOf(frame("CQ 1AT106")) }
        val receiver = LiveSlotReceiver(engine, backgroundScope, null, Ft8DecodeProfile.STANDARD, StandardTestDispatcher(testScheduler))
        repeat(75) { receiver.offer(slot(it)); runCurrent() }
        assertEquals(60, receiver.uiState.value.rows.size)
        engine.frames = emptyList(); receiver.offer(slot(75)); runCurrent()
        assertEquals(76, receiver.uiState.value.decodedSlots)
        assertEquals(slot(75).utcStartMillis, receiver.uiState.value.lastSlotUtcMillis)
        assertEquals(60, receiver.uiState.value.rows.size)
        receiver.cancel()
    }
}

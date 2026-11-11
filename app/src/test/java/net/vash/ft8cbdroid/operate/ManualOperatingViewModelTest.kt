package net.vash.ft8cbdroid.operate

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import net.vash.ft8cbdroid.audio.CaptureUiState
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.core.qso.CbQsoPhase
import net.vash.ft8cbdroid.core.recording.*
import java.time.Instant
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.time.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ManualOperatingViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val stores = mutableListOf<ViewModelStore>()
    private val source = TimeSource { ClockReading(10_000_000_000L + scheduler.currentTime * 1_000_000, 1_800_000_000_000L + scheduler.currentTime) }
    private val clock = ReceiveClock(source)
    private var receiving = true
    private var recoveries = 0
    private var pauseMillis = 0L
    private var recoverInput: (() -> Unit)? = null
    private val coordinator = PhoneAudioCoordinator(object : PhoneAudioReceiver {
        override fun blockStarts(blocked: Boolean) {}
        override suspend fun pause(): (() -> Boolean)? {
            val wasReceiving = receiving; receiving = false
            delay(pauseMillis)
            return if (wasReceiving) { { if (recoverInput == null) receiving = true else recoverInput?.invoke(); recoveries++; true } } else null
        }
    })
    private class Engine : Ft8Engine {
        var encoded = mutableListOf<Pair<Ft8Payload77, Float>>()
        var encodeMillis = 0L
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile) = error("No self decoding")
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot {
            encoded += payload to audioFrequencyHz
            delay(encodeMillis)
            wait?.await()
            return Ft8PcmSlot.fromSamples(FloatArray(180000))
        }
    }
    private inner class Player : ScheduledAudioPreview {
        override val outputs = MutableStateFlow(listOf(PreviewOutput(3, "Speaker")))
        var slots = 0; var tones = 0; var fail = false
        var settings: PreviewSettings? = null
        var lastPlan: ScheduledSlot? = null
        override suspend fun measureLatency(outputId: Int) = OutputLatency(outputId, 0, 80_000_000, 1_000_000, source.read().monotonicNanos)
        override suspend fun play(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings, update: (PreviewReport) -> Unit) = error("Manual FT8 must use UTC")
        override suspend fun testTone(outputId: Int, frequencyHz: Float, peakAmplitude: Float) { tones++; delay(2200) }
        override suspend fun playScheduled(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings, plan: ScheduledSlot,
            clock: ReceiveClock, update: (PreviewReport) -> Unit) {
            slots++; lastPlan = plan; this.settings = settings
            while (plan.remaining(clock) > 0) delay(5)
            repeat(1500) {
                plan.validate(clock)
                if (fail) throw PreviewException("Audio focus interrupted.")
                update(PreviewReport("Speaker", 12000, 180000, it * 120L, 2.0, plan.utcMillis)); delay(10)
            }
            update(PreviewReport("Speaker", 12000, 180000, 180000, 2.0, plan.utcMillis))
        }
        override suspend fun playWaveform(slot: Ft8PcmSlot, outputId: Int, plan: ScheduledSlot, clock: ReceiveClock, update: (PreviewReport) -> Unit) {
            slots++; lastPlan = plan
            while (plan.remaining(clock) > 0) delay(5)
            repeat(1344) {
                plan.validate(clock)
                if (fail) throw PreviewException("Audio focus interrupted.")
                update(PreviewReport("Speaker", 12000, 161280, it * 120L, 2.0, plan.utcMillis)); delay(10)
            }
            update(PreviewReport("Speaker", 12000, 161280, 161280, 2.0, plan.utcMillis))
        }
        override fun close() {}
    }
    private fun model(engine: Engine = Engine(), player: Player = Player(), prepare: suspend () -> Unit = {}, rx: Flow<CaptureUiState> = emptyFlow(), recovery: suspend () -> Unit = {}): ManualOperatingViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, viewModelFactory { initializer {
            ManualOperatingViewModel(engine, player, coordinator, clock, { receiving }, prepare, dispatcher, rx, prepareAutomaticReceive = {}, awaitAutomaticRecovery = recovery)
        } })[ManualOperatingViewModel::class.java]
    }
    private fun sync() { val r = source.read(); clock.apply(TimeMeasurement("NTP/test", r.monotonicNanos, r.unixMillis * 1_000_000, 1.0, r)) }
    private suspend fun TestScope.ready(vm: ManualOperatingViewModel) {
        runCurrent(); vm.context(StationIdentity("1AT106", "JN45"), true, 27_265_000, 1)
        vm.output(3); vm.calibrate(); advanceTimeBy(501); runCurrent()
        assertNotNull(vm.uiState.value.latency)
    }
    @Before fun before() { Dispatchers.setMain(dispatcher); sync() }
    @After fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    @Test fun explicitEnableAndSendFreezeOnePayloadAndRecordOnlyACompleteSlotWithoutRepeating() = runTest(dispatcher) {
        val engine = Engine(); val player = Player(); val vm = model(engine, player); ready(vm)
        vm.send(); runCurrent(); assertEquals(0, player.slots)
        vm.period(SlotPeriod.ODD)
        vm.enableTx(); assertTrue(vm.uiState.value.armed); assertEquals(0, player.slots)
        vm.send(); vm.send(); runCurrent()
        assertFalse(receiving); assertEquals(1, player.slots); assertTrue(vm.uiState.value.sent.isEmpty())
        advanceTimeBy(60000); runCurrent()
        assertFalse(vm.uiState.value.armed); assertFalse(vm.uiState.value.busy); assertTrue(receiving)
        val sent = vm.uiState.value.sent.single()
        assertEquals("CQ 1AT106", sent.text); assertEquals(27_265_000L, sent.referenceDialHz)
        assertEquals(15_000L, sent.utcMillis % 30_000)
        assertEquals((CbMessageCodec.pack(sent.text) as PackResult.Encoded).payload, engine.encoded.single().first)
        assertEquals(1, player.slots); vm.send(); advanceTimeBy(60000); assertEquals(1, player.slots)
    }
    @Test fun selectingACompletePeerSuggestsReplyReportAndOppositePeriodWithoutEnablingTx() = runTest(dispatcher) {
        val vm = model(); ready(vm)
        vm.selectPeer(ManualPeer("26AT101", 1250, -12, SlotPeriod.ODD))
        assertEquals("<26AT101> 1AT106", vm.uiState.value.text)
        assertEquals("1250", vm.uiState.value.frequency); assertEquals(SlotPeriod.ODD, vm.uiState.value.period)
        assertFalse(vm.uiState.value.armed)
        vm.choose(ManualMessage.REPORT); assertEquals("26AT101 -12", vm.uiState.value.text)
        vm.choose(ManualMessage.ROGER); assertEquals("26AT101 R-12", vm.uiState.value.text)
        vm.choose(ManualMessage.RR73); assertEquals("26AT101 RR73", vm.uiState.value.text)
    }
    @Test fun everyRelevantEditDisarmsAndCancelsAQueuedSnapshotWithoutAddingSent() = runTest(dispatcher) {
        val edits: List<(ManualOperatingViewModel) -> Unit> = listOf(
            { it.peer("26AT101") }, { it.report("-12") }, { it.frequency("1200") }, { it.level("20") },
            { it.period(SlotPeriod.ODD) }, { it.choose(ManualMessage.CUSTOM) }, { it.custom("HELLO") },
            { it.freeText(true) }, { it.acceptChanges(true) }, { it.output(null) }, { it.stop() })
        for (edit in edits) {
            receiving = true; sync()
            val p = Player(); val vm = model(player = p); ready(vm)
            vm.enableTx(); vm.send(); runCurrent(); val before = recoveries
            edit(vm); runCurrent(); advanceTimeBy(60000); runCurrent()
            assertFalse(vm.uiState.value.armed); assertFalse(vm.uiState.value.busy)
            assertTrue(vm.uiState.value.sent.isEmpty()); assertEquals(before, recoveries)
        }
    }
    @Test fun incompleteStationWrongSourceMissingReceiveStaleLatencyAndClockResetCannotArm() = runTest(dispatcher) {
        val p = Player(); val vm = model(player = p); ready(vm)
        receiving = false; vm.enableTx(); assertFalse(vm.uiState.value.armed)
        receiving = true; clock.reset(); vm.enableTx(); assertFalse(vm.uiState.value.armed)
        sync(); advanceTimeBy(300001); vm.enableTx(); assertFalse(vm.uiState.value.armed)
        vm.context(StationIdentity("1AT106", "JN45"), false, 27_265_000, 2); assertFalse(vm.uiState.value.canArm)
        vm.context(StationIdentity("K1ABC", "JN45"), true, 27_265_000, 3); assertFalse(vm.uiState.value.canArm)
        vm.peer("123ABCDEFGHIJKLMNO"); assertEquals("123ABCDEFGHIJKLMNO", vm.uiState.value.peer)
        assertNotNull(vm.uiState.value.validation) // An oversized identity must not silently become a different peer.
        assertEquals(0, p.slots)
    }
    @Test fun lossyCustomTextNeedsFreshConsentAndUnsupportedInputNeverEncodes() = runTest(dispatcher) {
        val vm = model(); ready(vm)
        vm.custom("<1AT106> 26AT101 R-10"); assertNotNull(vm.uiState.value.validation)
        vm.acceptChanges(true); vm.enableTx(); assertTrue(vm.uiState.value.armed)
        vm.custom("<...> 26AT101"); assertFalse(vm.uiState.value.acceptChanges); assertFalse(vm.uiState.value.canArm)
        assertFalse(vm.uiState.value.armed)
    }
    @Test fun clockChangesDuringGenerationRejectTheOldIntentAndExplicitRetryIsRequired() = runTest(dispatcher) {
        val engine = Engine().apply { wait = CompletableDeferred() }; val p = Player(); val vm = model(engine, p); ready(vm)
        vm.enableTx(); vm.send(); runCurrent(); sync(); engine.wait!!.complete(Unit); runCurrent()
        assertEquals(0, p.slots); assertNotNull(vm.uiState.value.error); assertFalse(vm.uiState.value.armed)
    }
    @Test fun actualBackendErrorsPreparationTimeoutProfileAndNewProcessCannotRearm() = runTest(dispatcher) {
        val p = Player().apply { fail = true }; val vm = model(player = p); ready(vm)
        vm.enableTx(); vm.send(); advanceTimeBy(60000); runCurrent()
        assertNotNull(vm.uiState.value.error); assertFalse(vm.uiState.value.armed); assertTrue(vm.uiState.value.sent.isEmpty())
        receiving = true; sync()
        val waiting = model(prepare = { awaitCancellation() }); runCurrent()
        waiting.context(StationIdentity("1AT106", "JN45"), true, 27_265_000, 1); waiting.output(3)
        waiting.calibrate(); advanceTimeBy(15001); runCurrent(); assertNotNull(waiting.uiState.value.error)
        vm.stopForProfile(); assertNull(vm.uiState.value.outputId); assertNull(vm.uiState.value.latency)
        val fresh = model(); runCurrent(); assertFalse(fresh.uiState.value.armed); assertTrue(fresh.uiState.value.sent.isEmpty())
    }
    @Test fun boundedToneIsExplicitDisarmsAndNeverAddsASentFt8Message() = runTest(dispatcher) {
        val p = Player(); val vm = model(player = p); ready(vm)
        vm.enableTx(); vm.testTone(); runCurrent(); assertFalse(vm.uiState.value.armed)
        advanceTimeBy(3000); runCurrent()
        assertEquals(1, p.tones); assertTrue(vm.uiState.value.sent.isEmpty()); assertTrue(receiving)
    }

    private fun liveInput(run: Long = 1) = CaptureUiState(inputs = listOf(AudioInput(1, "Phone", AudioInputKind.PHONE)),
        selectedId = 1, phase = CapturePhase.CAPTURING, decodeLive = true, receiveRun = run,
        receiveStartedUtcMillis = 1_800_000_000_000L, receiveTimeRevision = clock.revision)
    private fun decoded(text: String, slot: Long): LiveDecodeRow {
        val encoded = CbMessageCodec.pack(text) as PackResult.Encoded
        val session = CbMessageSession("1AT106")
        session.learnCall("26AT101"); session.learnCall("161XZ085"); session.commitPending()
        return LiveDecodeRow(RecordedDecode(Ft8DecodedFrame(encoded.payload, .5f, 1500f, 10, -12f), session.unpack(encoded.payload),
            Instant.ofEpochMilli(slot), Instant.ofEpochMilli(slot + 500), 0f, setOf(RecordedDecodeFlag.CRC_LDPC_VALID)), "NTP/test", 1.0)
    }
    private fun automaticInput() = liveInput().copy(earlyWindows = true)
    private suspend fun TestScope.at(ms: Long) { advanceTimeBy(ms - scheduler.currentTime); runCurrent() }
    @Test fun automaticOutputWaitsForRealRecoveryAndStopCancelsThatWaitWithoutRearming() = runTest(dispatcher) {
        val rx = MutableStateFlow(automaticInput()); val player = Player()
        val vm = model(player = player, rx = rx, recovery = { rx.first { it.phase == CapturePhase.CAPTURING } })
        ready(vm)
        recoverInput = {
            rx.value = rx.value.copy(phase = CapturePhase.STARTING)
            launch { delay(500); receiving = true; rx.value = rx.value.copy(phase = CapturePhase.CAPTURING) }
        }
        vm.startAutomatic(true); runCurrent(); at(43_700)
        assertTrue(vm.uiState.value.busy); assertTrue(vm.uiState.value.autoSequence.active)
        at(44_200)
        assertFalse(vm.uiState.value.busy); assertTrue(vm.uiState.value.autoSequence.active)
        assertNull(vm.uiState.value.error); assertEquals(1, vm.uiState.value.sent.size)
        at(58_700)
        rx.value = rx.value.copy(live = LiveReceiveState(lastSlotUtcMillis = 1_800_000_045_000))
        runCurrent(); at(73_700)
        assertTrue(vm.uiState.value.busy)
        vm.stop(); runCurrent(); at(75_000)
        assertFalse(vm.uiState.value.busy); assertFalse(vm.uiState.value.autoSequence.active)
        assertFalse(vm.uiState.value.armed); assertEquals(2, vm.uiState.value.sent.size)
        at(110_000); assertEquals(2, player.slots)
    }
    @Test fun immediateResponseReservesTimeForActualInputCleanupAndWaveformGeneration() = runTest(dispatcher) {
        val rx = MutableStateFlow(automaticInput()); val player = Player()
        val engine = Engine().also { it.encodeMillis = 100 }
        val vm = model(engine, player, rx = rx); ready(vm)
        pauseMillis = 350
        vm.startAutomatic(true); runCurrent()
        at(44_000); assertEquals(1, vm.uiState.value.sent.size)
        at(58_700)
        rx.value = rx.value.copy(receiveRun = 2, receiveStartedUtcMillis = 1_800_000_044_000,
            live = LiveReceiveState(lastSlotUtcMillis = 1_800_000_045_000,
                rows = listOf(decoded("<1AT106> 26AT101", 1_800_000_045_000).copy(capturedSamples = 162000))))
        runCurrent(); at(74_000)
        assertNull(vm.uiState.value.error)
        assertEquals("26AT101 -12", vm.uiState.value.sent.first().text)
        assertEquals(1_800_000_060_000, vm.uiState.value.sent.first().utcMillis)
        vm.stop()
    }
    @Test fun automaticCallingConsumesEarlyQualifiedRepliesAndConfirmsOnlyActualWaveforms() = runTest(dispatcher) {
        val rx = MutableStateFlow(automaticInput()); val player = Player(); val vm = model(player = player, rx = rx)
        ready(vm); vm.startAutomatic(true); runCurrent(); assertTrue(vm.uiState.value.autoSequence.active)
        at(44_000); assertEquals("CQ 1AT106", vm.uiState.value.sent.single().text)
        suspend fun incoming(time: Long, slot: Long, text: String, run: Long) {
            at(time)
            rx.value = rx.value.copy(receiveRun = run, receiveStartedUtcMillis = 1_800_000_000_000L + slot - 1000,
                live = LiveReceiveState(lastSlotUtcMillis = 1_800_000_000_000L + slot,
                    rows = listOf(decoded(text, 1_800_000_000_000L + slot).copy(capturedSamples = 162000))))
            runCurrent()
        }
        incoming(59_200, 45_000, "<1AT106> 26AT101", 2)
        at(74_000); assertEquals("26AT101 -12", vm.uiState.value.sent.first().text)
        incoming(89_200, 75_000, "1AT106 R-07", 3)
        at(104_000); assertEquals("26AT101 RR73", vm.uiState.value.sent.first().text)
        incoming(119_200, 105_000, "1AT106 73", 4)
        assertEquals(CbQsoPhase.COMPLETE, vm.uiState.value.qso?.phase)
        at(119_240)
        assertFalse(vm.uiState.value.autoSequence.active); assertTrue(vm.uiState.value.autoSequence.completed)
        assertEquals(3, vm.uiState.value.sent.size); assertTrue(vm.uiState.value.sent.all { it.frames == 161280L })
        assertFalse(vm.uiState.value.armed); at(180_000); assertEquals(3, player.slots)
    }
    @Test fun automaticStopEditsClockLossAndBackendFailureCannotRepeatOrRearm() = runTest(dispatcher) {
        for (change in listOf<(ManualOperatingViewModel) -> Unit>({ it.stop() }, { it.frequency("1200") }, { clock.reset() })) {
            receiving = true; sync()
            val rx = MutableStateFlow(automaticInput().copy(receiveTimeRevision = clock.revision,
                receiveStartedUtcMillis = source.read().unixMillis))
            val player = Player(); val vm = model(player = player, rx = rx); ready(vm); vm.startAutomatic(true); runCurrent()
            repeat(2000) { if (vm.uiState.value.busy) return@repeat; advanceTimeBy(20); runCurrent() }
            assertTrue(vm.uiState.value.busy)
            change(vm); runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertFalse(vm.uiState.value.autoSequence.active); assertFalse(vm.uiState.value.armed); assertTrue(vm.uiState.value.sent.isEmpty())
        }
    }
    @Test fun automaticAnswerCompletesAfterTheConfirmed73AndNeverRestartsCalling() = runTest(dispatcher) {
        val rx = MutableStateFlow(automaticInput()); val player = Player(); val vm = model(player = player, rx = rx)
        ready(vm); vm.selectPeer(ManualPeer("26AT101", 1500, -12, SlotPeriod.ODD)); vm.startAutomatic(false); runCurrent()
        at(29_000); assertEquals("<26AT101> 1AT106", vm.uiState.value.sent.single().text)
        at(44_200)
        rx.value = rx.value.copy(receiveRun = 2, receiveStartedUtcMillis = 1_800_000_029_000,
            live = LiveReceiveState(lastSlotUtcMillis = 1_800_000_030_000,
                rows = listOf(decoded("1AT106 -07", 1_800_000_030_000).copy(capturedSamples = 162000))))
        runCurrent(); at(59_000); assertEquals("26AT101 R-12", vm.uiState.value.sent.first().text)
        at(74_200)
        rx.value = rx.value.copy(receiveRun = 3, receiveStartedUtcMillis = 1_800_000_059_000,
            live = LiveReceiveState(lastSlotUtcMillis = 1_800_000_060_000,
                rows = listOf(decoded("1AT106 RR73", 1_800_000_060_000).copy(capturedSamples = 162000))))
        runCurrent(); at(89_000)
        assertEquals("26AT101 73", vm.uiState.value.sent.first().text)
        assertEquals(CbQsoPhase.COMPLETE, vm.uiState.value.qso?.phase)
        assertTrue(vm.uiState.value.autoSequence.completed); assertFalse(vm.uiState.value.autoSequence.active)
        assertFalse(vm.uiState.value.armed); at(150_000); assertEquals(3, player.slots)
    }
    @Test fun qualifiedLiveReportSuggestsRReportWithoutChangingOrArmingManualTx() = runTest(dispatcher) {
        val rx = MutableStateFlow(liveInput()); val player = Player(); val vm = model(player = player, rx = rx)
        ready(vm); vm.selectPeer(ManualPeer("26AT101", 1500, -12, SlotPeriod.EVEN)); vm.enableTx(); vm.send()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(vm.uiState.value.qso?.replySent == true)
        val oldText = vm.uiState.value.text
        advanceTimeBy(16_000); rx.value = rx.value.copy(live = LiveReceiveState(rows = listOf(decoded("1AT106 -07", 1_800_000_060_000))))
        runCurrent()
        val value = vm.uiState.value
        assertEquals(-7, value.qso?.receivedReport); assertEquals(ManualMessage.ROGER, value.suggestion?.kind)
        assertEquals(oldText, value.text); assertEquals(ManualMessage.REPLY, value.choice); assertFalse(value.armed)
        assertEquals(1, player.slots)
        vm.useSuggestion(); runCurrent()
        assertEquals("26AT101 R-12", vm.uiState.value.text); assertFalse(vm.uiState.value.armed)
        assertEquals(1, player.slots)
        vm.resetQso(); runCurrent()
        assertEquals(CbQsoPhase.IDLE, vm.uiState.value.qso?.phase); assertNull(vm.uiState.value.suggestion)
    }
    @Test fun livePartnerReplyingElsewhereDisarmsAndAbortsWithoutChangingPeer() = runTest(dispatcher) {
        val rx = MutableStateFlow(liveInput()); val player = Player(); val vm = model(player = player, rx = rx)
        ready(vm); vm.selectPeer(ManualPeer("26AT101", 1500, -12, SlotPeriod.EVEN)); vm.enableTx(); vm.send()
        advanceTimeBy(60_000); runCurrent(); vm.enableTx(); assertTrue(vm.uiState.value.armed)
        advanceTimeBy(16_000); rx.value = rx.value.copy(live = LiveReceiveState(rows = listOf(decoded("<161XZ085> 26AT101", 1_800_000_060_000))))
        runCurrent()
        assertEquals(CbQsoPhase.ABORTED, vm.uiState.value.qso?.phase); assertFalse(vm.uiState.value.armed)
        assertEquals("26AT101", vm.uiState.value.peer); assertNull(vm.uiState.value.suggestion)
        assertEquals(1, player.slots)
    }
    @Test fun receiverEndClockResetAndSourceChangeDiscardSuggestionsWithoutSilentRecovery() = runTest(dispatcher) {
        val rx = MutableStateFlow(liveInput()); val vm = model(rx = rx); ready(vm)
        vm.selectPeer(ManualPeer("26AT101", 1500, -12, SlotPeriod.EVEN)); runCurrent()
        assertNotNull(vm.uiState.value.suggestion)
        rx.value = rx.value.copy(phase = CapturePhase.IDLE); runCurrent()
        assertEquals(CbQsoPhase.ABORTED, vm.uiState.value.qso?.phase); assertNull(vm.uiState.value.suggestion)
        rx.value = liveInput(2); runCurrent()
        assertEquals(CbQsoPhase.ABORTED, vm.uiState.value.qso?.phase)
        vm.selectPeer(ManualPeer("26AT101", 1500, -12, SlotPeriod.EVEN)); runCurrent()
        assertNotNull(vm.uiState.value.suggestion)
        clock.reset(); rx.value = rx.value.copy(live = LiveReceiveState(decodedSlots = 1)); runCurrent()
        assertEquals(CbQsoPhase.ABORTED, vm.uiState.value.qso?.phase); assertNull(vm.uiState.value.suggestion)
        vm.context(StationIdentity("1AT106", "JN45"), false, 27_265_000, 2)
        assertNull(vm.uiState.value.qso); assertFalse(vm.uiState.value.armed)
    }

    @Test fun clockResetDuringOwnOutputPauseInvalidatesQsoAndItsPendingTicket() = runTest(dispatcher) {
        val rx = MutableStateFlow(liveInput()); val engine = Engine().also { it.wait = CompletableDeferred() }
        val vm = model(engine = engine, rx = rx); ready(vm)
        vm.selectPeer(ManualPeer("26AT101", 1500, -12, SlotPeriod.EVEN)); runCurrent()
        vm.enableTx(); vm.send(); runCurrent()
        assertTrue(vm.uiState.value.busy); assertNotNull(vm.uiState.value.qso?.pending)
        clock.reset(); rx.value = rx.value.copy(outputBusy = true); runCurrent()
        assertEquals(CbQsoPhase.ABORTED, vm.uiState.value.qso?.phase); assertNull(vm.uiState.value.qso?.pending)
        assertNull(vm.uiState.value.suggestion)
        vm.stop(); runCurrent(); assertTrue(vm.uiState.value.sent.isEmpty())
    }
}

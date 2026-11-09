package net.vash.ft8cbdroid.operate

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val coordinator = PhoneAudioCoordinator(object : PhoneAudioReceiver {
        override fun blockStarts(blocked: Boolean) {}
        override suspend fun pause(): (() -> Boolean)? {
            val wasReceiving = receiving; receiving = false
            return if (wasReceiving) { { receiving = true; recoveries++; true } } else null
        }
    })
    private class Engine : Ft8Engine {
        var encoded = mutableListOf<Pair<Ft8Payload77, Float>>()
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile) = error("No self decoding")
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot {
            encoded += payload to audioFrequencyHz
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
        override fun close() {}
    }
    private fun model(engine: Engine = Engine(), player: Player = Player(), prepare: suspend () -> Unit = {}): ManualOperatingViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, viewModelFactory { initializer {
            ManualOperatingViewModel(engine, player, coordinator, clock, { receiving }, prepare, dispatcher)
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
        vm.enableTx(); assertTrue(vm.uiState.value.armed); assertEquals(0, player.slots)
        vm.send(); vm.send(); runCurrent()
        assertFalse(receiving); assertEquals(1, player.slots); assertTrue(vm.uiState.value.sent.isEmpty())
        advanceTimeBy(60000); runCurrent()
        assertFalse(vm.uiState.value.armed); assertFalse(vm.uiState.value.busy); assertTrue(receiving)
        val sent = vm.uiState.value.sent.single()
        assertEquals("CQ 1AT106", sent.text); assertEquals(27_265_000L, sent.referenceDialHz)
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
}

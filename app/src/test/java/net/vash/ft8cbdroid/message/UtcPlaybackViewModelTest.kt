package net.vash.ft8cbdroid.message

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.time.*
import net.vash.ft8cbdroid.core.message.EncodingMode
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class UtcPlaybackViewModelTest {
    private val scheduler=TestCoroutineScheduler()
    private val dispatcher=StandardTestDispatcher(scheduler)
    private val stores=mutableListOf<ViewModelStore>()
    private val source=TimeSource { ClockReading(10_000_000_000L+scheduler.currentTime*1_000_000,1_800_000_001_000L+scheduler.currentTime) }
    private val clock=ReceiveClock(source)
    private class Player(val clock: ReceiveClock) : ScheduledAudioPreview {
        override val outputs=MutableStateFlow(listOf(PreviewOutput(5,"Speaker")))
        var measurements=0;var scheduled=0;var signals=0;var active=0
        var slot: Ft8PcmSlot?=null;var settings: PreviewSettings?=null
        var blockMeasure=false
        override suspend fun measureLatency(outputId: Int): OutputLatency {
            measurements++;if(blockMeasure) awaitCancellation()
            return OutputLatency(outputId,0,80_000_000,1_000_000,clock.source.read().monotonicNanos)
        }
        override suspend fun play(slot: Ft8PcmSlot,outputId: Int,settings: PreviewSettings,update: (PreviewReport)->Unit) = error("Use scheduled playback")
        override suspend fun playScheduled(slot: Ft8PcmSlot,outputId: Int,settings: PreviewSettings,plan: ScheduledSlot,clock: ReceiveClock,update: (PreviewReport)->Unit) {
            scheduled++;active++;this.slot=slot;this.settings=settings
            try {
                while(plan.remaining(clock)>0) delay(10)
                signals++;awaitCancellation()
            } finally { active-- }
        }
        override fun close() {}
    }
    private val engine=object : Ft8Engine {
        override suspend fun decode(slot: Ft8PcmSlot,profile: Ft8DecodeProfile)=error("No decoding")
        override suspend fun encode(payload: Ft8Payload77,audioFrequencyHz: Float,peakAmplitude: Float)=
            Ft8PcmSlot.fromSamples(FloatArray(180000) { if(it==9600) peakAmplitude else 0f })
    }
    private fun model(p: Player): WaveformViewModel {
        val store=ViewModelStore().also(stores::add)
        return ViewModelProvider(store,viewModelFactory { initializer {
            WaveformViewModel(engine,WaveformExporter { _,_-> },dispatcher,p,clock=clock)
        } })[WaveformViewModel::class.java]
    }
    private fun sync() { val now=source.read();clock.apply(TimeMeasurement("NTP/test",now.monotonicNanos,now.unixMillis*1_000_000,1.0,now)) }
    private fun WaveformViewModel.ready() { generate("CQ 1AT106",EncodingMode.AUTOMATIC);selectOutput(5) }
    @Before fun before() { Dispatchers.setMain(dispatcher);sync() }
    @After fun after() { stores.forEach { it.clear() };Dispatchers.resetMain() }
    @Test fun explicitCalibrationAndQueueOwnOneImmutableMessageWithoutArmingAnotherSlot()=runTest(dispatcher) {
        val p=Player(clock);val vm=model(p);runCurrent();vm.ready();runCurrent()
        vm.queueSlot();runCurrent();assertEquals(0,p.scheduled)
        vm.measureLatency();runCurrent();assertEquals(1,p.measurements);assertNotNull(vm.uiState.value.latency)
        vm.previewGain("50");vm.queueSlot();vm.queueSlot();runCurrent()
        assertEquals(1,p.scheduled);assertEquals(50,p.settings!!.gainPercent);assertEquals(180000,p.slot!!.toFloatArray().size)
        assertNotNull(vm.uiState.value.queuedUtcMillis);assertEquals(0,p.signals)
        advanceTimeBy(34000);runCurrent();assertEquals(1,p.signals)
        vm.stopPreview();runCurrent();advanceTimeBy(60000);runCurrent();assertEquals(1,p.scheduled);assertEquals(0,p.active)
    }
    @Test fun messagePeriodOutputAndProfileEditsCancelQueuedAudioWithoutRetargeting()=runTest(dispatcher) {
        for (edit in listOf<(WaveformViewModel)->Unit>(
            {it.changeInput("CQ 26AT101",EncodingMode.AUTOMATIC)}, {it.period(SlotPeriod.ODD)},
            {it.selectOutput(null)}, {it.changeFrequency("1200")}, {it.previewRamp("50")})) {
            val p=Player(clock);val vm=model(p);runCurrent();vm.ready();runCurrent();vm.measureLatency();runCurrent()
            vm.queueSlot();runCurrent();edit(vm);runCurrent();advanceTimeBy(35000);runCurrent()
            assertEquals(0,p.signals);assertFalse(vm.uiState.value.isPlaying)
        }
        val p=Player(clock);val vm=model(p);runCurrent();vm.ready();runCurrent();vm.measureLatency();runCurrent();vm.queueSlot();runCurrent()
        vm.stopForProfile();runCurrent();assertNull(vm.uiState.value.latency);assertNull(vm.uiState.value.outputId);assertEquals(0,p.active)
    }
    @Test fun correctionChangeDuringWaitStopsAndRequiresAnExplicitRetry()=runTest(dispatcher) {
        val p=Player(clock);val vm=model(p);runCurrent();vm.ready();runCurrent();vm.measureLatency();runCurrent();vm.queueSlot();runCurrent()
        sync();advanceTimeBy(10);runCurrent()
        assertFalse(vm.uiState.value.isPlaying);assertNotNull(vm.uiState.value.error);assertEquals(0,p.signals)
        advanceTimeBy(35000);runCurrent();assertEquals(1,p.scheduled)
        vm.queueSlot();runCurrent();assertEquals(2,p.scheduled);vm.stopPreview();runCurrent()
    }
    @Test fun unsynchronizedAndStaleCalibrationFailWithoutOpeningAudio()=runTest(dispatcher) {
        val p=Player(clock);val vm=model(p);runCurrent();vm.ready();runCurrent();vm.measureLatency();runCurrent()
        clock.reset();vm.queueSlot();runCurrent();assertEquals(0,p.scheduled);assertNotNull(vm.uiState.value.error)
        sync();advanceTimeBy(300001);runCurrent();vm.queueSlot();runCurrent();assertEquals(0,p.scheduled)
        assertTrue(vm.uiState.value.error!!.contains("latency"))
    }
    @Test fun cancelledCalibrationAndNewProcessNeverRestoreAnArmedQueue()=runTest(dispatcher) {
        val p=Player(clock).apply { blockMeasure=true };val vm=model(p);runCurrent();vm.selectOutput(5);vm.measureLatency();runCurrent()
        vm.stopPreview();runCurrent();assertNull(vm.uiState.value.latency);assertFalse(vm.uiState.value.isPlaying)
        val fresh=model(Player(clock));runCurrent();assertNull(fresh.uiState.value.queuedUtcMillis);assertNull(fresh.uiState.value.latency)
    }
}

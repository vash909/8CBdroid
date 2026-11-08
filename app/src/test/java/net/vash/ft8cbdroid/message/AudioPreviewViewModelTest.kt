package net.vash.ft8cbdroid.message

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.EncodingMode
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private class Player : AudioPreview {
        override val outputs = MutableStateFlow(listOf(PreviewOutput(10,"Speaker")))
        var starts=0; var active=0; var closed=false
        var finish = CompletableDeferred<Unit>()
        var cleanup: CompletableDeferred<Unit>? = null
        var fail=false
        var publish: ((PreviewReport)->Unit)? = null
        override suspend fun play(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings, update: (PreviewReport)->Unit) {
            starts++;active++;publish=update
            try {
                finish.await()
                if (fail) throw PreviewException("Audio focus was interrupted.")
                update(PreviewReport("Speaker",12000,180000,180000))
            } finally { withContext(NonCancellable) { cleanup?.await(); active-- } }
        }
        override fun close() { closed=true }
    }
    private val engine = object : Ft8Engine {
        override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile) = error("No decode")
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float) =
            Ft8PcmSlot.fromSamples(FloatArray(180000) { if (it==9600) peakAmplitude else 0f })
    }
    private fun model(player: Player, prepare: suspend () -> Unit = {}): WaveformViewModel {
        val store=ViewModelStore().also(stores::add)
        return ViewModelProvider(store,viewModelFactory { initializer {
            WaveformViewModel(engine,WaveformExporter { _,_-> },dispatcher,player,prepare)
        } })[WaveformViewModel::class.java]
    }
    private fun WaveformViewModel.generate()=generate("CQ 1AT106",EncodingMode.AUTOMATIC)
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    @Test fun generationAndOutputDiscoveryNeverStartPlaybackWithoutExplicitSelectionAndPlay()= runTest(dispatcher) {
        val player=Player();val vm=model(player);runCurrent();vm.generate();advanceUntilIdle()
        assertNull(vm.uiState.value.outputId);assertFalse(vm.uiState.value.canPlay);assertEquals(0,player.starts)
        vm.selectOutput(10);assertTrue(vm.uiState.value.canPlay);assertEquals(0,player.starts)
        vm.playPreview();vm.playPreview();runCurrent();assertEquals(1,player.starts)
        player.finish.complete(Unit);runCurrent();assertFalse(vm.uiState.value.isPlaying)
        assertEquals(180000L,vm.uiState.value.playback!!.playedFrames);assertEquals(0,player.active)
    }
    @Test fun stoppingWaitsForCleanupRejectsDuplicatesAndDiscardsLateReports()= runTest(dispatcher) {
        val player=Player().apply { cleanup=CompletableDeferred() };val vm=model(player);runCurrent()
        vm.generate();runCurrent();vm.selectOutput(10);vm.playPreview();runCurrent();vm.stopPreview();runCurrent()
        assertTrue(vm.uiState.value.isPlaying);vm.playPreview();assertEquals(1,player.starts)
        player.publish!!(PreviewReport("stale",12000,1,1));assertNull(vm.uiState.value.playback)
        player.cleanup!!.complete(Unit);runCurrent();assertFalse(vm.uiState.value.isPlaying);assertEquals(0,player.active)
    }
    @Test fun cancelledPreparationCannotStartTheSpeakerLater()= runTest(dispatcher) {
        val gate=CompletableDeferred<Unit>();val player=Player();val vm=model(player) { gate.await() };runCurrent()
        vm.generate();runCurrent();vm.selectOutput(10);vm.playPreview();runCurrent();vm.stopPreview();gate.complete(Unit);runCurrent()
        assertEquals(0,player.starts);assertFalse(vm.uiState.value.isPlaying)
    }
    @Test fun stalledReceiveCleanupHasABoundedDeadlineAndNeverStartsTheSpeaker()= runTest(dispatcher) {
        val player=Player();val vm=model(player) { awaitCancellation() };runCurrent()
        vm.generate();runCurrent();vm.selectOutput(10);vm.playPreview();runCurrent()
        advanceTimeBy(15_000);runCurrent()
        assertEquals(0,player.starts);assertFalse(vm.uiState.value.isPlaying)
        assertTrue(vm.uiState.value.error!!.contains("cleanup timed out"));assertTrue(vm.uiState.value.canPlay)
    }
    @Test fun outputLossAndReappearanceNeverFallBackOrResume()= runTest(dispatcher) {
        val player=Player();val vm=model(player);runCurrent();vm.generate();runCurrent();vm.selectOutput(10);vm.playPreview();runCurrent()
        player.outputs.value=emptyList();runCurrent();assertEquals(0,player.active);assertNull(vm.uiState.value.outputId)
        player.outputs.value=listOf(PreviewOutput(11,"New speaker"));runCurrent();assertFalse(vm.uiState.value.canPlay);assertEquals(1,player.starts)
    }
    @Test fun editsOutputClearingAndProfileChangesStopTheActualSession()= runTest(dispatcher) {
        val player=Player();val vm=model(player);runCurrent();vm.generate();runCurrent();vm.selectOutput(10);vm.playPreview();runCurrent()
        vm.previewRamp("50");runCurrent();assertEquals(0,player.active)
        vm.playPreview();runCurrent();vm.selectOutput(null);runCurrent();assertEquals(0,player.active)
        vm.selectOutput(10);vm.playPreview();runCurrent();vm.stopForProfile();runCurrent()
        assertNull(vm.uiState.value.outputId);assertEquals(0,player.active)
    }
    @Test fun audioFailureRequiresExplicitRetryAndNewProcessDoesNotRestoreOutputOrPcm()= runTest(dispatcher) {
        val player=Player().apply { fail=true };val vm=model(player);runCurrent();vm.generate();runCurrent();vm.selectOutput(10)
        vm.playPreview();runCurrent();player.finish.complete(Unit);runCurrent();assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isPlaying);assertEquals(1,player.starts)
        player.fail=false;player.finish=CompletableDeferred();vm.playPreview();runCurrent();assertEquals(2,player.starts)
        vm.stopPreview();runCurrent()
        val fresh=model(Player());runCurrent();assertNull(fresh.uiState.value.summary);assertNull(fresh.uiState.value.outputId)
    }
    @Test fun invalidSettingsAndRegenerationCannotReplayAnOldWaveform()= runTest(dispatcher) {
        val player=Player();val vm=model(player);runCurrent();vm.generate();runCurrent();vm.selectOutput(10)
        vm.previewGain("0");assertFalse(vm.uiState.value.canPlay);vm.playPreview();assertEquals(0,player.starts)
        vm.previewGain("50");vm.playPreview();runCurrent();vm.generate();runCurrent();assertEquals(0,player.active)
        vm.playPreview();runCurrent();vm.changeInput("CQ 26AT101",EncodingMode.AUTOMATIC);runCurrent()
        assertFalse(vm.uiState.value.canPlay);assertEquals(0,player.active)
    }
}

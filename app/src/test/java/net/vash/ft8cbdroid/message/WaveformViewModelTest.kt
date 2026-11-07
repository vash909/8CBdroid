package net.vash.ft8cbdroid.message

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.message.EncodingMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaveformViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private class Engine : Ft8Engine {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile) = error("Unexpected decode")
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot {
            calls++
            val currentGate = gate
            if (currentGate != null) withContext(NonCancellable) { currentGate.await() }
            return Ft8PcmSlot.fromSamples(FloatArray(Ft8PcmSlot.sampleCount) { if (it == 9600) peakAmplitude else 0f })
        }
    }
    private fun model(engine: Engine = Engine(), exporter: WaveformExporter = WaveformExporter { _, _ -> }) =
        WaveformViewModel(engine, exporter, dispatcher)
    private fun WaveformViewModel.generate(text: String = "CQ 1AT106") = generate(text, EncodingMode.AUTOMATIC)

    @Test fun generatedResultUsesExplicitSettingsAndChangesInvalidateIt() = runTest(dispatcher) {
        val vm = model()
        vm.changeFrequency("1250.5"); vm.changeLevel("25")
        vm.generate(); advanceUntilIdle()
        assertTrue(vm.uiState.value.canSave)
        assertEquals(1250.5f, vm.uiState.value.summary!!.frequencyHz)
        assertEquals(.25f, vm.uiState.value.summary!!.peak)
        vm.changeLevel("50")
        assertFalse(vm.uiState.value.canSave)
        assertNull(vm.uiState.value.summary)
    }

    @Test fun lossesNeedConsentAndAnyNewInputResetsThatConsent() = runTest(dispatcher) {
        val engine = Engine(); val vm = model(engine)
        vm.generate("<1AT106> 26AT101 R-10"); advanceUntilIdle()
        assertEquals(0, engine.calls); assertNotNull(vm.uiState.value.error)
        vm.acceptChanges(true)
        vm.generate("<1AT106> 26AT101 R-10"); advanceUntilIdle()
        assertEquals(1, engine.calls)
        assertEquals("<1AT106> 26AT101", vm.uiState.value.summary!!.encodedText)
        vm.changeInput("CQ 1AT106 JN45", EncodingMode.AUTOMATIC)
        assertFalse(vm.uiState.value.acceptContentChanges)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test fun editsDiscardLateNativeResultsAndAllowANewGeneration() = runTest(dispatcher) {
        val engine = Engine().apply { gate = CompletableDeferred() }; val vm = model(engine)
        val oldGate = engine.gate!!
        vm.generate(); runCurrent()
        vm.changeFrequency("1500")
        engine.gate = null
        vm.generate(); advanceUntilIdle()
        assertEquals(1500f, vm.uiState.value.summary!!.frequencyHz)
        oldGate.complete(Unit); advanceUntilIdle()
        assertEquals(1500f, vm.uiState.value.summary!!.frequencyHz)
        assertFalse(vm.uiState.value.isGenerating)
    }

    @Test fun cancelAndDuplicateRequestsCannotPublishAnOldWaveform() = runTest(dispatcher) {
        val engine = Engine().apply { gate = CompletableDeferred() }; val vm = model(engine)
        vm.generate(); runCurrent(); vm.generate(); runCurrent()
        assertEquals(1, engine.calls)
        vm.cancel(); engine.gate!!.complete(Unit); advanceUntilIdle()
        assertNull(vm.uiState.value.summary)
        assertFalse(vm.uiState.value.canSave)
        assertEquals("Generation cancelled.", vm.uiState.value.notice)
    }

    @Test fun exportUsesTheSnapshotSelectedBeforeLaterEditsAndSupportsRetry() = runTest(dispatcher) {
        var fail = true; var saved: ByteArray? = null
        val vm = model(exporter = WaveformExporter { _, bytes ->
            if (fail) throw IOException("provider failure")
            saved = bytes.copyOf()
        })
        vm.generate(); advanceUntilIdle()
        assertTrue(vm.prepareExport()); assertFalse(vm.prepareExport())
        vm.export("test:wave"); advanceUntilIdle()
        assertNotNull(vm.uiState.value.error); assertTrue(vm.uiState.value.canSave)
        fail = false
        assertTrue(vm.prepareExport())
        vm.changeLevel("100"); vm.generate(); advanceUntilIdle()
        vm.export("test:wave"); advanceUntilIdle()
        val sample = java.nio.ByteBuffer.wrap(saved!!).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(44 + 9600 * 2)
        assertEquals(13107, sample.toInt())
        assertEquals(1f, vm.uiState.value.summary!!.peak)
        assertNull(vm.uiState.value.notice)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test fun cancelledPickerKeepsResultAndInvalidSettingsNeverStartDsp() = runTest(dispatcher) {
        val engine = Engine(); val vm = model(engine)
        vm.changeFrequency("NaN"); vm.generate(); advanceUntilIdle()
        assertEquals(0, engine.calls); assertNotNull(vm.uiState.value.error)
        vm.changeFrequency("1000"); vm.generate(); advanceUntilIdle()
        assertTrue(vm.prepareExport()); vm.export(null)
        assertTrue(vm.uiState.value.canSave)
        assertFalse(vm.uiState.value.isChoosingDestination)
        assertFalse(model().uiState.value.canSave)
    }
}

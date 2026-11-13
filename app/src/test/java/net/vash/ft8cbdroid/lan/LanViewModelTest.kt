package net.vash.ft8cbdroid.lan

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.vash.ft8cbdroid.core.BackendResult
import net.vash.ft8cbdroid.protocol.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test fun serviceCanReadAnIoFailureBeforeUiDeliveryAndStopClearsIt() = runTest {
        val bridge = FakeBridge(); val vm = create(bridge)
        vm.start(); advanceUntilIdle()
        bridge.state.value = bridge.state.value.copy(phase = LanPhase.ERROR, error = "Socket interrupted")
        assertFalse(vm.receivingOrStarting)
        assertEquals("Socket interrupted", vm.failureReason)
        vm.stop(); advanceUntilIdle(); assertNull(vm.failureReason)
    }
    @Test fun servicePromotionPrecedesSocketBindAndRepeatedStartKeepsOneListener() = runTest {
        val bridge = FakeBridge()
        var promoted = false
        val store = ViewModelStore().also(stores::add)
        val vm = ViewModelProvider(store, viewModelFactory { initializer { LanViewModel(bridge) { promoted } } })[LanViewModel::class.java]
        vm.start(); advanceUntilIdle(); assertEquals(0, bridge.starts)
        promoted = true
        vm.start(); advanceUntilIdle(); assertEquals(1, bridge.starts)
        vm.start(); advanceUntilIdle(); assertEquals(1, bridge.starts)
        vm.stop(); advanceUntilIdle(); assertFalse(vm.uiState.value.active)
    }
    @Test fun validatesInputAndLocksConfigurationWhileListening() = runTest {
        val bridge = FakeBridge()
        val viewModel = create(bridge)
        viewModel.changePort("0")
        viewModel.start()
        viewModel.changePort("2237")
        viewModel.changeAddress("localhost")
        viewModel.start()
        advanceUntilIdle()
        assertEquals(0, bridge.starts)
        viewModel.changeAddress("192.168.1.10")
        viewModel.start()
        viewModel.start()
        advanceUntilIdle()
        assertEquals(1, bridge.starts)
        assertEquals("192.168.1.10", bridge.settings!!.expectedDesktopAddress)
        assertFalse(viewModel.uiState.value.canEdit)
        viewModel.changePort("9999")
        assertEquals("2237", viewModel.uiState.value.port)
    }

    @Test fun stopDuringBindIsAppliedAfterStartCompletes() = runTest {
        val bridge = FakeBridge().apply { bindGate = CompletableDeferred() }
        val viewModel = create(bridge)
        viewModel.start()
        advanceUntilIdle()
        viewModel.stop() // Same operation used when the activity goes into the background.
        advanceUntilIdle()
        assertEquals(0, bridge.stops)
        bridge.bindGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, bridge.stops)
        assertEquals(LanPhase.STOPPED, viewModel.uiState.value.connection.phase)
        assertTrue(viewModel.uiState.value.canStart)
    }

    @Test fun bindFailureAllowsRetry() = runTest {
        val bridge = FakeBridge().apply { fail = true }
        val viewModel = create(bridge)
        viewModel.start()
        advanceUntilIdle()
        assertEquals("Port unavailable", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.canStart)
        bridge.fail = false
        viewModel.start()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertEquals(LanPhase.LISTENING, viewModel.uiState.value.connection.phase)
    }

    @Test fun profileSwitchWaitsForBindThenClearsThePeerAndConfiguresWithoutListening() = runTest {
        val bridge = FakeBridge().apply { bindGate = CompletableDeferred() }
        val vm = create(bridge); vm.start(); advanceUntilIdle()
        val update = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            vm.stopForProfile()
            vm.configureProfile(net.vash.ft8cbdroid.core.profile.ProfileLan(2240, "192.0.2.8"))
        }
        advanceUntilIdle(); assertFalse(update.isCompleted)
        bridge.bindGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, bridge.starts); assertEquals(1, bridge.stops)
        assertEquals(LanPhase.STOPPED, vm.uiState.value.connection.phase)
        assertEquals("2240",vm.uiState.value.port); assertEquals("192.0.2.8",vm.uiState.value.desktopAddress)
        assertNull(vm.uiState.value.connection.peer); assertTrue(vm.uiState.value.connection.decodes.isEmpty())
    }

    @Test fun exposesDesktopStatusFromBridge() = runTest {
        val bridge = FakeBridge()
        val viewModel = create(bridge)
        val status = DesktopStatus(27_265_000, "FT8", "26AT101", "-12", "FT8", true, false, true)
        bridge.state.value = LanState(phase = LanPhase.CONNECTED, status = status)
        advanceUntilIdle()
        assertEquals(status, viewModel.uiState.value.connection.status)
    }

    @Test fun clearingViewModelClosesBridge() = runTest {
        val bridge = FakeBridge()
        create(bridge)
        stores.single().clear()
        assertEquals(1, bridge.closes)
    }

    @Test fun replayIsExplicitRequiresConnectionAndReportsFailure() = runTest {
        val bridge = FakeBridge()
        val viewModel = create(bridge)
        viewModel.requestReplay()
        advanceUntilIdle()
        assertEquals(0, bridge.replays)
        bridge.connect()
        advanceUntilIdle()
        assertEquals(0, bridge.replays)
        assertTrue(viewModel.uiState.value.canReplay)
        bridge.failReplay = true
        viewModel.requestReplay()
        advanceUntilIdle()
        assertEquals("Replay unavailable", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.canReplay)
        bridge.failReplay = false
        viewModel.requestReplay()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertEquals(2, bridge.replays)
    }

    @Test fun replayInFlightBlocksDuplicatesAndSerializesBackgroundStop() = runTest {
        val bridge = FakeBridge().apply { replayGate = CompletableDeferred(); connect() }
        val viewModel = create(bridge)
        viewModel.requestReplay()
        viewModel.requestReplay()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canReplay)
        viewModel.stop()
        advanceUntilIdle()
        assertEquals(0, bridge.stops)
        bridge.replayGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, bridge.replays)
        assertEquals(1, bridge.stops)
        assertEquals(LanPhase.STOPPED, viewModel.uiState.value.connection.phase)
    }

    @Test fun exposesHistoryAndClearsItWithoutDisconnecting() = runTest {
        val bridge = FakeBridge().apply { connect() }
        val viewModel = create(bridge)
        val decode = LanDecode(true, 0, -17, 0.25, 1000, "~", "CQ 1AT106 JN45")
        bridge.state.value = bridge.state.value.copy(decodes = listOf(decode))
        advanceUntilIdle()
        assertEquals(listOf(decode), viewModel.uiState.value.connection.decodes)
        viewModel.clearDecodes()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.connection.decodes.isEmpty())
        assertEquals(LanPhase.CONNECTED, viewModel.uiState.value.connection.phase)
        assertEquals(0, bridge.replays)
    }

    private fun create(bridge: FakeBridge): LanViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, viewModelFactory { initializer { LanViewModel(bridge) } })[LanViewModel::class.java]
    }

    private class FakeBridge : WsjtCbBridge {
        override val state = MutableStateFlow(LanState())
        var starts = 0
        var stops = 0
        var closes = 0
        var replays = 0
        var failReplay = false
        var fail = false
        var settings: LanSettings? = null
        var bindGate: CompletableDeferred<Unit>? = null
        var replayGate: CompletableDeferred<Unit>? = null
        fun connect() {
            state.value = LanState(LanPhase.CONNECTED, 2237,
                DesktopPeer("127.0.0.1", 40000, "WSJT-CB", 3, "1.4.1", "test"))
        }
        override suspend fun listen(settings: LanSettings): BackendResult {
            starts++
            this.settings = settings
            bindGate?.await()
            if (fail) return BackendResult.Unavailable("Port unavailable")
            state.value = LanState(LanPhase.LISTENING, settings.listenPort)
            return BackendResult.Success
        }
        override suspend fun stop() { stops++; state.value = LanState() }
        override suspend fun requestReplay(): BackendResult {
            replays++
            replayGate?.await()
            return if (failReplay) BackendResult.Unavailable("Replay unavailable") else BackendResult.Success
        }
        override fun clearDecodes() { state.value = state.value.copy(decodes = emptyList()) }
        override fun close() { closes++; state.value = LanState() }
    }
}

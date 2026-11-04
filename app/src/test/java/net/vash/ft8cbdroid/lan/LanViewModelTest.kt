package net.vash.ft8cbdroid.lan

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

    private fun create(bridge: FakeBridge): LanViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, viewModelFactory { initializer { LanViewModel(bridge) } })[LanViewModel::class.java]
    }

    private class FakeBridge : WsjtCbBridge {
        override val state = MutableStateFlow(LanState())
        var starts = 0
        var stops = 0
        var closes = 0
        var fail = false
        var settings: LanSettings? = null
        var bindGate: CompletableDeferred<Unit>? = null
        override suspend fun listen(settings: LanSettings): BackendResult {
            starts++
            this.settings = settings
            bindGate?.await()
            if (fail) return BackendResult.Unavailable("Port unavailable")
            state.value = LanState(LanPhase.LISTENING, settings.listenPort)
            return BackendResult.Success
        }
        override suspend fun stop() { stops++; state.value = LanState() }
        override fun close() { closes++; state.value = LanState() }
    }
}

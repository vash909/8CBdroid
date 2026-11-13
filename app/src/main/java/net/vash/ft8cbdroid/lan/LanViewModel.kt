package net.vash.ft8cbdroid.lan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.vash.ft8cbdroid.core.BackendResult
import net.vash.ft8cbdroid.protocol.LanPhase
import net.vash.ft8cbdroid.protocol.LanSettings
import net.vash.ft8cbdroid.protocol.LanState
import net.vash.ft8cbdroid.protocol.UdpWsjtCbBridge
import net.vash.ft8cbdroid.protocol.WsjtCbBridge
import net.vash.ft8cbdroid.protocol.WsjtCbProtocol

data class LanUiState(
    val port: String = WsjtCbProtocol.DEFAULT_LISTEN_PORT.toString(),
    val desktopAddress: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val connection: LanState = LanState(),
) {
    val active: Boolean get() = connection.phase == LanPhase.LISTENING || connection.phase == LanPhase.CONNECTED
    val canEdit: Boolean get() = !active && !busy
    val validPort: Boolean get() = port.toIntOrNull()?.let { it in 1..65535 } == true
    val validAddress: Boolean get() = LanSettings.isDesktopAddressValid(desktopAddress.trim())
    val canStart: Boolean get() = canEdit && validPort && validAddress
    val canReplay: Boolean get() = !busy && connection.phase == LanPhase.CONNECTED
}

class LanViewModel(private val bridge: WsjtCbBridge, private val startSession: (() -> Boolean)? = null) : ViewModel() {
    private val inputs = MutableStateFlow(LanUiState())
    private val commands = Mutex()
    val uiState = combine(inputs, bridge.state) { input, connection -> input.copy(connection = connection) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LanUiState())

    private fun current() = inputs.value.copy(connection = bridge.state.value)

    fun changePort(value: String) {
        if (current().canEdit) inputs.update { it.copy(port = value.filter { char -> char in '0'..'9' }.take(5), error = null) }
    }

    fun changeAddress(value: String) {
        if (current().canEdit) inputs.update { it.copy(desktopAddress = value, error = null) }
    }

    fun start() {
        val input = current()
        if (!input.canStart || startSession?.invoke() == false) return
        inputs.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            commands.withLock {
                try {
                    val result = bridge.listen(LanSettings(input.port.toInt(), input.desktopAddress.trim()))
                    if (result is BackendResult.Unavailable) inputs.update { it.copy(error = result.reason) }
                } finally {
                    inputs.update { it.copy(busy = false) }
                }
            }
        }
    }

    /** Explicit stop, serialized behind any bind in progress. Navigation does not stop the listener. */
    fun stop() {
        viewModelScope.launch {
            commands.withLock {
                bridge.stop()
                inputs.update { it.copy(busy = false, error = null) }
            }
        }
    }

    suspend fun stopForProfile() {
        commands.withLock {
            bridge.stop()
            bridge.clearDecodes()
            inputs.update { it.copy(busy = false, error = null) }
        }
    }
    suspend fun configureProfile(profile: net.vash.ft8cbdroid.core.profile.ProfileLan) {
        profile.validate()
        commands.withLock {
            check(!current().active && !current().busy) { "Stop the LAN listener before applying a profile." }
            inputs.value = LanUiState(port = profile.port.toString(), desktopAddress = profile.desktopAddress)
        }
    }

    fun requestReplay() {
        if (!current().canReplay) return
        inputs.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            commands.withLock {
                try {
                    val result = bridge.requestReplay()
                    if (result is BackendResult.Unavailable) inputs.update { it.copy(error = result.reason) }
                } finally {
                    inputs.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun clearDecodes() { bridge.clearDecodes() }

    override fun onCleared() { bridge.close() }

    companion object {
        fun factory(startSession: (() -> Boolean)? = null) = viewModelFactory { initializer { LanViewModel(UdpWsjtCbBridge(), startSession) } }
    }
}

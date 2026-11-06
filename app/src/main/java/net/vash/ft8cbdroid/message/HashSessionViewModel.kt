package net.vash.ft8cbdroid.message

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.message.CbMessageSession
import net.vash.ft8cbdroid.core.message.DecodedMessage
import net.vash.ft8cbdroid.core.message.UnpackResult

data class HashSessionUiState(
    val stationCall: String? = null,
    val learnedCount: Int = 0,
    val pendingCount: Int = 0,
    val capacityReached: Boolean = false,
    val message: DecodedMessage? = null,
    val error: String? = null,
)

/** Offline receiver simulation only. No network, DSP, persistent cache or TX state. */
class HashSessionViewModel : ViewModel() {
    private val session = CbMessageSession()
    private val state = MutableStateFlow(HashSessionUiState())
    val uiState = state.asStateFlow()

    fun changeStation(savedCall: String?) {
        val call = savedCall?.takeIf(CbCallsign::isValid)?.let(CbCallsign::normalize)
        if (call != session.stationCall) {
            session.reset(call)
            publish()
        }
    }

    fun receive(payload: Ft8Payload77) {
        when (val result = session.unpack(payload)) {
            is UnpackResult.Decoded -> publish(message = result.message)
            is UnpackResult.Unsupported -> publish(error = result.reason)
        }
    }

    fun commit() {
        session.commitPending()
        // Keep the previous receive snapshot; committing does not rewrite history.
        publish(state.value.message, state.value.error)
    }

    fun reset() {
        session.reset(session.stationCall)
        publish()
    }

    private fun publish(message: DecodedMessage? = null, error: String? = null) {
        state.value = HashSessionUiState(session.stationCall, session.learnedCount,
            session.pendingCount, session.capacityReached, message, error)
    }
}

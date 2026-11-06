package net.vash.ft8cbdroid.recording

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.vash.ft8cbdroid.core.Ft8Engine
import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.core.recording.*
import net.vash.ft8cbdroid.dsp.NativeFt8Engine

fun interface RecordedSlotReader { suspend fun read(document: String): Ft8PcmSlot }

class DocumentRecordedSlotReader(private val resolver: ContentResolver) : RecordedSlotReader {
    override suspend fun read(document: String): Ft8PcmSlot = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(Uri.parse(document)) ?: throw IOException("No input stream")
        stream.use { RecordedSlotWav.read(it) }
    }
}

data class RecordedSlotUiState(
    val utc: String = "",
    val isChoosing: Boolean = false,
    val isDecoding: Boolean = false,
    val result: RecordedSlotResult? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val canImport: Boolean get() = !isChoosing && !isDecoding
}

/** One explicit file request; version checks discard cancelled or stale native results. */
class RecordedSlotViewModel(
    engine: Ft8Engine,
    private val reader: RecordedSlotReader,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val decoder = RecordedSlotDecoder(engine)
    private val state = MutableStateFlow(RecordedSlotUiState())
    val uiState = state.asStateFlow()
    private var stationCall: String? = null
    private var version = 0L
    private var selection: Pair<Long, RecordedSlotContext>? = null
    private var job: Job? = null

    fun changeStation(call: String?) {
        if (call == stationCall) return
        stationCall = call
        clear()
    }
    fun changeUtc(value: String) {
        if (value == state.value.utc) return
        clear()
        state.value = state.value.copy(utc = value)
    }
    fun clear() {
        version++
        job?.cancel()
        job = null
        selection = null
        state.value = state.value.copy(isChoosing = false, isDecoding = false, result = null, error = null, notice = null)
    }
    fun cancel() {
        clear()
        state.value = state.value.copy(notice = "Recorded-slot decode cancelled.")
    }
    fun prepareImport(): Boolean {
        if (!state.value.canImport) return false
        val context = try { RecordedSlotContext.parse(state.value.utc) }
        catch (error: IllegalArgumentException) {
            state.value = state.value.copy(error = error.message)
            return false
        }
        clear()
        selection = version to context
        state.value = state.value.copy(isChoosing = true)
        return true
    }
    fun importDocument(document: String?) {
        val (ticket, context) = selection ?: return
        selection = null
        state.value = state.value.copy(isChoosing = false)
        if (document == null) {
            state.value = state.value.copy(notice = "No WAV selected.")
            return
        }
        val call = stationCall
        state.value = state.value.copy(isDecoding = true)
        job = viewModelScope.launch {
            try {
                val result = withContext(worker) { decoder.decode(reader.read(document), context, call) }
                if (ticket == version) state.value = state.value.copy(result = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                if (ticket == version) state.value = state.value.copy(error = error.message ?: "Invalid recorded slot.")
            } catch (_: UnsatisfiedLinkError) {
                if (ticket == version) state.value = state.value.copy(error = "The offline DSP backend is unavailable.")
            } catch (_: Exception) {
                if (ticket == version) state.value = state.value.copy(error = "Could not read or decode the WAV. Select the document again to retry.")
            } finally {
                if (ticket == version) {
                    state.value = state.value.copy(isDecoding = false)
                    job = null
                }
            }
        }
    }
    companion object {
        fun factory(resolver: ContentResolver) = viewModelFactory {
            initializer { RecordedSlotViewModel(NativeFt8Engine(), DocumentRecordedSlotReader(resolver)) }
        }
    }
}

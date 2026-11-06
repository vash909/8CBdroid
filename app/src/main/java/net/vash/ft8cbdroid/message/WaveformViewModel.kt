package net.vash.ft8cbdroid.message

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.Ft8Engine
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.dsp.NativeFt8Engine

data class WaveformSummary(val encodedText: String, val frequencyHz: Float, val levelPercent: Float, val peak: Float)
data class WaveformUiState(
    val frequency: String = "1000",
    val levelPercent: String = "40",
    val acceptContentChanges: Boolean = false,
    val isGenerating: Boolean = false,
    val isChoosingDestination: Boolean = false,
    val isSaving: Boolean = false,
    val summary: WaveformSummary? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val canSave: Boolean get() = summary != null && !isGenerating && !isChoosingDestination && !isSaving
}

fun interface WaveformExporter { suspend fun write(destination: String, bytes: ByteArray) }

class DocumentWaveformExporter(private val resolver: ContentResolver) : WaveformExporter {
    override suspend fun write(destination: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val stream = resolver.openOutputStream(Uri.parse(destination), "wt") ?: throw IOException("No output stream")
        stream.use { it.write(bytes) }
    }
}

/** Owns one replaceable offline generation and immutable export snapshots. No device audio. */
class WaveformViewModel(
    engine: Ft8Engine,
    private val exporter: WaveformExporter,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val generator = OfflineFt8Generator(engine)
    private val state = MutableStateFlow(WaveformUiState())
    val uiState = state.asStateFlow()
    private var input: Pair<String, EncodingMode>? = null
    private var version = 0L
    private var job: Job? = null
    private var wav: ByteArray? = null
    private var pendingExport: Pair<Long, ByteArray>? = null

    fun changeInput(text: String, mode: EncodingMode) {
        val next = text to mode
        if (next != input) {
            input = next
            invalidate()
        }
    }
    fun changeFrequency(value: String) {
        if (value != state.value.frequency) {
            invalidate()
            state.value = state.value.copy(frequency = value)
        }
    }
    fun changeLevel(value: String) {
        if (value != state.value.levelPercent) {
            invalidate()
            state.value = state.value.copy(levelPercent = value)
        }
    }
    fun acceptChanges(value: Boolean) {
        invalidate()
        state.value = state.value.copy(acceptContentChanges = value)
    }
    fun cancel() {
        invalidate()
        state.value = state.value.copy(notice = "Generation cancelled.")
    }

    private fun invalidate() {
        version++
        job?.cancel()
        job = null
        wav = null
        state.value = state.value.copy(isGenerating = false, summary = null,
            acceptContentChanges = false, error = null, notice = null)
    }

    fun generate(text: String, mode: EncodingMode) {
        changeInput(text, mode)
        if (state.value.isGenerating) return
        val settings = try {
            Ft8GenerationSettings(
                requireNotNull(state.value.frequency.toFloatOrNull()) { "Enter a base audio frequency in Hz." },
                requireNotNull(state.value.levelPercent.toFloatOrNull()) { "Enter a peak level in percent." } / 100f,
            )
        } catch (error: IllegalArgumentException) {
            state.value = state.value.copy(error = error.message)
            return
        }
        val ticket = ++version
        val consent = state.value.acceptContentChanges
        wav = null
        state.value = state.value.copy(isGenerating = true, summary = null, error = null, notice = null)
        job = viewModelScope.launch {
            try {
                val prepared = withContext(worker) {
                    val result = generator.generate(text, mode, settings, consent)
                    val bytes = (result as? WaveformGenerationResult.Generated)?.waveform?.wavBytes()
                    val peak = (result as? WaveformGenerationResult.Generated)?.waveform?.pcm
                        ?.toFloatArray()?.maxOf { abs(it) }
                    Triple(result, bytes, peak)
                }
                if (ticket != version) return@launch
                when (val result = prepared.first) {
                    is WaveformGenerationResult.Unsupported -> state.value = state.value.copy(error = result.reason)
                    is WaveformGenerationResult.ConfirmationRequired -> state.value = state.value.copy(
                        error = "Confirm the encoded message before generating: ${result.encodedText}")
                    is WaveformGenerationResult.Generated -> {
                        wav = requireNotNull(prepared.second)
                        state.value = state.value.copy(summary = WaveformSummary(result.waveform.message.transmittedText,
                            settings.audioFrequencyHz, settings.peakAmplitude * 100, requireNotNull(prepared.third)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnsatisfiedLinkError) {
                if (ticket == version) state.value = state.value.copy(error = "The offline DSP backend is unavailable.")
            } catch (_: Exception) {
                if (ticket == version) state.value = state.value.copy(error = "Could not generate the offline waveform.")
            } finally {
                if (ticket == version) {
                    state.value = state.value.copy(isGenerating = false)
                    job = null
                }
            }
        }
    }

    fun prepareExport(): Boolean {
        if (!state.value.canSave) return false
        pendingExport = version to requireNotNull(wav)
        state.value = state.value.copy(isChoosingDestination = true, error = null, notice = null)
        return true
    }

    fun export(destination: String?) {
        val snapshot = pendingExport ?: return
        pendingExport = null
        state.value = state.value.copy(isChoosingDestination = false)
        if (destination == null) return
        state.value = state.value.copy(isSaving = true)
        viewModelScope.launch {
            try {
                exporter.write(destination, snapshot.second)
                if (snapshot.first == version) state.value = state.value.copy(notice = "WAV saved.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (snapshot.first == version) state.value = state.value.copy(error = "Could not save the WAV. Choose a destination and retry.")
            } finally {
                state.value = state.value.copy(isSaving = false)
            }
        }
    }

    companion object {
        fun factory(resolver: ContentResolver) = viewModelFactory {
            initializer { WaveformViewModel(NativeFt8Engine(), DocumentWaveformExporter(resolver)) }
        }
    }
}

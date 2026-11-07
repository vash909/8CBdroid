package net.vash.ft8cbdroid.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Read-only, normalized 12 kHz PCM. Neither callback may block the recorder. */
interface PcmMicrophoneCapture : MicrophoneCapture {
    suspend fun capturePcm(inputId: Int, update: suspend (CaptureMetrics) -> Unit,
        pcm: (FloatArray) -> Unit, timed: ((TimedCaptureChunk) -> Unit)? = null)
}

data class SpectrumFrame(val endSample: Long, val dbfs: List<Float>)
data class SpectrumState(val frames: List<SpectrumFrame> = emptyList(), val processed: Long = 0,
    val replaced: Long = 0, val computeMicros: Long = 0, val error: String? = null, val sessionId: Long = 0)

/** One-sided Hann-windowed peak amplitude in dBFS, not an SNR or RF power measurement. */
class SpectrumFft {
    private val window = DoubleArray(SIZE) { .5 - .5 * cos(2 * PI * it / (SIZE - 1)) }
    private val gain = window.sum()
    fun analyze(pcm: FloatArray): List<Float> {
        require(pcm.size == SIZE && pcm.all { it.isFinite() })
        val real = DoubleArray(SIZE) { pcm[it] * window[it] }
        val imag = DoubleArray(SIZE)
        var j = 0
        for (i in 1 until SIZE) {
            var bit = SIZE shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val value = real[i]; real[i] = real[j]; real[j] = value }
        }
        var length = 2
        while (length <= SIZE) {
            val angle = -2 * PI / length; val stepR = cos(angle); val stepI = sin(angle)
            for (base in 0 until SIZE step length) {
                var wr = 1.0; var wi = 0.0
                for (offset in 0 until length / 2) {
                    val a = base + offset; val b = a + length / 2
                    val br = real[b] * wr - imag[b] * wi; val bi = real[b] * wi + imag[b] * wr
                    real[b] = real[a] - br; imag[b] = imag[a] - bi
                    real[a] += br; imag[a] += bi
                    val next = wr * stepR - wi * stepI; wi = wr * stepI + wi * stepR; wr = next
                }
            }
            length *= 2
        }
        return List(BINS) { bin ->
            val amplitude = hypot(real[bin], imag[bin]) * (if (bin == 0) 1 else 2) / gain
            (20 * log10(max(amplitude, 1e-5))).toFloat().coerceIn(FLOOR_DB, 0f)
        }
    }
    companion object {
        const val RATE = 12000
        const val SIZE = 4096
        const val HOP = 6000 // At most two updates per second of captured audio.
        const val BINS = 1025 // 0–3000 Hz inclusive, 2.9296875 Hz/bin.
        const val FLOOR_DB = -100f
        fun frequency(bin: Int) = bin * RATE.toFloat() / SIZE
    }
}

/** Copy windows on the reader; do FFT/history work on one worker with one replaceable pending window. */
class SpectrumMonitor(scope: CoroutineScope, worker: CoroutineDispatcher = Dispatchers.Default,
    analyze: (FloatArray) -> List<Float> = SpectrumFft()::analyze) {
    private data class Window(val end: Long, val pcm: FloatArray)
    private val sessionId = sessions.incrementAndGet()
    private val state = MutableStateFlow(SpectrumState(sessionId = sessionId))
    val uiState = state.asStateFlow()
    private val dropped = AtomicLong()
    private val pending = Channel<Window>(1, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }
    private val ring = FloatArray(SpectrumFft.SIZE)
    private var total = 0L
    private var position = 0
    private val job = scope.launch(worker) {
        for (window in pending) {
            try {
                val start = System.nanoTime()
                val values = analyze(window.pcm)
                ensureActive()
                val old = state.value
                state.value = SpectrumState((listOf(SpectrumFrame(window.end, values)) + old.frames).take(HISTORY),
                    old.processed + 1, dropped.get(), (System.nanoTime() - start) / 1000, sessionId = sessionId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = "Could not analyze this spectrum window. Reception continues.") }
        }
    }
    init { job.invokeOnCompletion { pending.cancel() } }
    fun offer(pcm: FloatArray) {
        if (!job.isActive) return
        for (value in pcm) {
            ring[position] = value; position = (position + 1) % ring.size; total++
            if (total >= ring.size && (total - ring.size) % SpectrumFft.HOP == 0L) {
                val frame = FloatArray(ring.size)
                ring.copyInto(frame, 0, position); ring.copyInto(frame, ring.size - position, 0, position)
                pending.trySend(Window(total, frame))
            }
        }
    }
    fun cancel() { pending.cancel(); job.cancel() }
    companion object { const val HISTORY = 96; private val sessions = AtomicLong() }
}

enum class OffsetCursor { RX, TX }

/** Temporary display selections. These neither restrict the decoder nor tune a radio or arm TX. */
data class SpectrumView(val startHz: Float = 0f, val spanHz: Float = 3000f,
    val rxHz: Int = 1000, val txHz: Int = 1500, val cursor: OffsetCursor = OffsetCursor.RX) {
    init {
        require(spanHz in setOf(750f, 1500f, 3000f) && startHz.isFinite() && startHz >= 0 && startHz + spanHz <= 3000)
        require(rxHz in 200..2950 && txHz in 200..2950)
    }
    val selectedHz get() = if (cursor == OffsetCursor.RX) rxHz else txHz
    fun select(hz: Float): SpectrumView {
        require(hz.isFinite())
        val value = hz.roundToInt().coerceIn(200, 2950)
        return if (cursor == OffsetCursor.RX) copy(rxHz = value) else copy(txHz = value)
    }
    fun at(fraction: Float) = startHz + fraction.coerceIn(0f, 1f) * spanHz
    fun fraction(hz: Float) = (hz - startHz) / spanHz
    fun zoom(span: Float): SpectrumView = copy(spanHz = span,
        startHz = (selectedHz - span / 2).coerceIn(0f, 3000 - span))
    fun pan(direction: Int) = copy(startHz = (startHz + direction.coerceIn(-1, 1) * spanHz / 2).coerceIn(0f, 3000 - spanHz))
}

data class SpectrumMarker(val frequencyHz: Float, val utcMillis: Long)

/** Frequency indicators for the latest decoded local slot, not timestamps of waterfall pixels. */
fun localSpectrumMarkers(rows: List<LiveDecodeRow>): List<SpectrumMarker> {
    val utc = rows.maxOfOrNull { it.decode.slotStartUtc.toEpochMilli() } ?: return emptyList()
    return rows.filter { it.decode.slotStartUtc.toEpochMilli() == utc }
        .map { SpectrumMarker(it.decode.frame.audioFrequencyHz, utc) }
        .filter { it.frequencyHz.isFinite() && it.frequencyHz in 0f..3000f }
        .distinctBy { it.frequencyHz }.take(LiveSlotReceiver.MAX_ROWS)
}

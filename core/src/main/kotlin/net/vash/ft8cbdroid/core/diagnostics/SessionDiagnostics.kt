package net.vash.ft8cbdroid.core.diagnostics

/** Typed technical data only: never accept PCM, messages, callsigns, addresses or exception text. */
enum class ReceiveDiagnosticEvent {
    INPUT_STARTED, INPUT_FAILED, WINDOW_COMPLETE, WINDOW_DISCARDED,
    DECODE_COMPLETE, DECODE_FAILED, WINDOW_UNPROCESSED,
}
fun interface ReceiveDiagnosticSink { fun record(event: ReceiveDiagnosticEvent, decodeMillis: Long?) }
enum class DiagnosticSource { IDLE, MICROPHONE, LAN }
enum class DiagnosticPhase { STOPPED, STARTING, RUNNING, STOPPING, ERROR }
enum class DiagnosticEnd { RUNNING, OPERATOR, LIMIT, INTERRUPTED }
data class DiagnosticObservation(
    val source: DiagnosticSource = DiagnosticSource.IDLE, val phase: DiagnosticPhase = DiagnosticPhase.STOPPED,
    val decoder: net.vash.ft8cbdroid.core.Ft8DecodeProfile = net.vash.ft8cbdroid.core.Ft8DecodeProfile.STANDARD,
    val inputRun: Long? = null, val inputRate: Int? = null, val clippedSamples: Long? = null, val driftPpm: Double? = null,
    val retainedLanRows: Int = 0, val timeRevision: Long? = null, val timeUsable: Boolean = false,
    val timeUncertaintyMillis: Double? = null, val txActive: Boolean = false,
)
data class DiagnosticResources(
    val pssKiB: Long? = null, val processCpuMillis: Long? = null, val batteryPercent: Int? = null,
    val batteryTemperatureC: Double? = null, val plugged: Boolean? = null, val thermalStatus: Int? = null,
)
data class DiagnosticSample(val elapsedMillis: Long, val observation: DiagnosticObservation, val resources: DiagnosticResources)
data class DiagnosticReport(
    val elapsedMillis: Long, val end: DiagnosticEnd,
    val events: Map<ReceiveDiagnosticEvent, Long>, val decodeCount: Long,
    val decodeTotalMillis: Long, val decodeMaxMillis: Long?, val samples: List<DiagnosticSample>,
)

/** A process-local, explicit one-hour session. O(1) event updates; at most 122 low-rate samples. */
class SessionDiagnostics(private val durationMillis: Long = MAX_DURATION_MILLIS) {
    init { require(durationMillis in 1..MAX_DURATION_MILLIS) }
    private var started: Long? = null
    private var end = DiagnosticEnd.INTERRUPTED
    private var elapsed = 0L
    private val events = mutableMapOf<ReceiveDiagnosticEvent, Long>()
    private var decodeCount = 0L
    private var decodeTotal = 0L
    private var decodeMax: Long? = null
    private val samples = ArrayList<DiagnosticSample>()
    @Synchronized fun start(now: Long) {
        require(now >= 0); check(end != DiagnosticEnd.RUNNING)
        started = now; end = DiagnosticEnd.RUNNING; elapsed = 0
        events.clear(); samples.clear(); decodeCount = 0; decodeTotal = 0; decodeMax = null
    }
    private fun advance(now: Long): Boolean {
        if (end != DiagnosticEnd.RUNNING) return false
        elapsed = maxOf(elapsed, (now - requireNotNull(started)).coerceIn(0, durationMillis))
        if (elapsed >= durationMillis) { end = DiagnosticEnd.LIMIT; return false }
        return true
    }
    @Synchronized fun event(now: Long, event: ReceiveDiagnosticEvent, decodeMillis: Long? = null) {
        if (!advance(now)) return
        events[event] = (events[event] ?: 0) + 1
        if (event == ReceiveDiagnosticEvent.DECODE_COMPLETE && decodeMillis != null && decodeMillis in 0..60_000) {
            decodeCount++; decodeTotal += decodeMillis; decodeMax = maxOf(decodeMax ?: 0, decodeMillis)
        }
    }
    @Synchronized fun sample(now: Long, observation: DiagnosticObservation, resources: DiagnosticResources) {
        if (end != DiagnosticEnd.RUNNING) return
        advance(now)
        val previous = samples.lastOrNull()
        if (samples.size < MAX_SAMPLES && (previous == null || elapsed - previous.elapsedMillis >= SAMPLE_INTERVAL_MILLIS || end == DiagnosticEnd.LIMIT))
            samples.add(DiagnosticSample(elapsed, observation, resources))
    }
    @Synchronized fun stop(now: Long): DiagnosticReport {
        if (advance(now)) end = DiagnosticEnd.OPERATOR
        return snapshot()
    }
    @Synchronized fun snapshot() = DiagnosticReport(elapsed, end, events.toMap(), decodeCount, decodeTotal, decodeMax, samples.toList())
    companion object {
        const val MAX_DURATION_MILLIS = 3_600_000L
        const val SAMPLE_INTERVAL_MILLIS = 30_000L
        const val MAX_SAMPLES = 122
    }
}

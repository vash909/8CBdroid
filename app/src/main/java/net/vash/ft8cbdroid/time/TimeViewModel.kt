package net.vash.ft8cbdroid.time

import android.content.Context
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.vash.ft8cbdroid.core.time.*
import net.vash.ft8cbdroid.ntp.*

data class TimeUiState(
    val draft: TimeSettings = TimeSettings(), val saved: TimeSettings? = null,
    val loading: Boolean = true, val saving: Boolean = false, val busy: String? = null,
    val awaitingGpsPermission: Boolean = false, val measurement: TimeMeasurement? = null,
    val sourceDescription: String = "Not synchronized. Choose NTP or GPS.", val ageMillis: Long? = null,
    val error: String? = null, val notice: String? = null,
    val period: Ft8PeriodProgress? = null, val periodCorrected: Boolean = false,
    val refreshing: Boolean = false, val refreshPending: Boolean = false, val refreshWarning: String? = null,
) {
    val validation: String? get() = runCatching { value() }.exceptionOrNull()?.message
    fun value() = draft.validated()
    val canEdit get() = saved != null && !loading && !saving && busy == null && !awaitingGpsPermission
    val hasChanges get() = runCatching { value() != saved }.getOrDefault(true)
    val canSave get() = canEdit && hasChanges && validation == null
    val canMeasure get() = canEdit && !hasChanges
    val canApply get() = canEdit && measurement != null && (measurement.source == "GPS/GNSS" || !hasChanges)
}

class TimeViewModel(private val repository: TimePreferencesRepository, private val ntp: NtpClient,
    private val gps: GpsTimeClient, val clock: ReceiveClock, private val tick: Boolean = true,
    private val automatic: Boolean = true) : ViewModel() {
    private val state = MutableStateFlow(TimeUiState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var refreshJob: Job? = null
    private var pendingRefresh: TimeMeasurement? = null
    private var nextAttemptNanos: Long? = null
    private var foreground = true
    private var operating = false
    private var idleBoundary: () -> Boolean = { !operating }
    fun idleBoundary(check: () -> Boolean) { idleBoundary = check }
    private var refreshGeneration = 0L
    fun foreground(active: Boolean) {
        foreground = active
    }
    fun operating(active: Boolean) { operating = active; if (!active && foreground) applyPendingAtBoundary() }
    private fun clearPeriodic() {
        refreshGeneration++; refreshJob?.cancel(); pendingRefresh = null
        nextAttemptNanos = null
        state.value = state.value.copy(refreshing = false, refreshPending = false, refreshWarning = null)
    }
    /** Main-thread tick; one bounded automatic attempt, then ten minutes before the next. */
    fun poll() {
        refresh()
        if (!foreground || state.value.busy != null || state.value.saving || state.value.loading) return
        if (!operating) applyPendingAtBoundary()
        if (!automatic || refreshJob?.isActive == true || pendingRefresh != null) return
        if (!operating && runCatching { clock.snapshot() }.isFailure && !idleBoundary()) return
        val now = clock.source.read().monotonicNanos
        val due = nextAttemptNanos ?: return
        if (now < due) return
        val settings = state.value.saved ?: return
        nextAttemptNanos = now + RETRY_NANOS
        val generation = refreshGeneration
        state.value = state.value.copy(refreshing = true)
        refreshJob = viewModelScope.launch {
            try {
                val measured = automaticMeasurement(settings)
                if (generation == refreshGeneration) {
                    pendingRefresh = measured
                    nextAttemptNanos = clock.source.read().monotonicNanos + RETRY_NANOS
                    state.value = state.value.copy(measurement = measured, refreshPending = true, refreshWarning = null)
                    if (!operating && foreground) applyPendingAtBoundary()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == refreshGeneration) {
                    nextAttemptNanos = clock.source.read().monotonicNanos + RETRY_NANOS
                    state.value = state.value.copy(refreshWarning =
                        "NTP and GPS are unavailable. Using the last correction if available; retrying in 10 minutes.")
                }
            } finally {
                if (generation == refreshGeneration) { state.value = state.value.copy(refreshing = false); refresh() }
            }
        }
    }
    private suspend fun automaticMeasurement(settings: TimeSettings): TimeMeasurement {
        for (useGps in if (settings.preferGps) listOf(true, false) else listOf(false, true)) {
            try { return withTimeout(if (useGps) 90_000L else 8_000L) { if (useGps) gps.measure() else ntp.measure(settings.host) } }
            catch (_: TimeoutCancellationException) { /* Try the other source after this bounded source timeout. */ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing permission/provider or failed packet is not a usable source. */ }
        }
        throw TimeException("Neither time source supplied a measurement.")
    }
    /** Only call with capture stopped and no queued/playing output. A successful refresh never arms TX. */
    fun applyPendingAtBoundary(audioPaused: Boolean = false): Boolean {
        if (!foreground || (!audioPaused && !idleBoundary())) return false
        val measured = pendingRefresh ?: return false
        pendingRefresh = null
        val result = runCatching {
            if (runCatching { clock.snapshot() }.isSuccess) clock.applyPeriodic(measured) else clock.apply(measured)
        }
        state.value = state.value.copy(refreshPending = false, refreshWarning = null,
            notice = result.exceptionOrNull()?.message ?: "Automatic time correction applied to this app only.")
        refresh()
        return result.isSuccess
    }
    init {
        load()
        if (tick) viewModelScope.launch {
            while (isActive) {
                poll(); delay(1000)
            }
        }
    }
    private fun refresh() {
        val sample = state.value.measurement
        val description = runCatching {
            val mapping = clock.snapshot()
            val age = ((clock.source.read().monotonicNanos - mapping.measuredAtNanos) / 1_000_000_000).coerceAtLeast(0)
            val quality = mapping.uncertaintyMillis?.let {
                java.lang.String.format(java.util.Locale.ROOT, "estimated uncertainty ±%.1f ms", it)
            } ?: "timing uncertainty not reported by receiver"
            "${mapping.source} • age $age s • $quality"
        }.getOrElse { it.message ?: "Time correction unavailable" }
        val displayMapping = runCatching { clock.snapshot() }.getOrNull()
        val displayNow = clock.source.read()
        val displayUtc = displayMapping?.at(displayNow.monotonicNanos)?.div(1_000_000) ?: displayNow.unixMillis
        state.value = state.value.copy(sourceDescription = description, ageMillis = sample?.ageMillis(displayNow),
            period = Ft8PeriodProgress.atUtcMillis(displayUtc), periodCorrected = displayMapping != null)
    }
    fun load() {
        if (job?.isActive == true) return
        state.value = state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val value = repository.settings.first().validated()
                clearPeriodic(); clock.reset()
                state.value = TimeUiState(draft = value, saved = value, loading = false)
                nextAttemptNanos = clock.source.read().monotonicNanos
                poll()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(loading = false, error = "Could not read time settings. Retry loading before editing.") }
        }
    }
    private fun change(block: (TimeUiState) -> TimeUiState) {
        if (state.value.canEdit) state.value = block(state.value).copy(measurement = null, ageMillis = null, error = null, notice = null)
    }
    fun preferGps(value: Boolean) = change { it.copy(draft = it.draft.copy(preferGps = value)) }
    fun selectPreset(id: String) = change { it.copy(draft = it.draft.copy(presetId = id)) }
    fun changeCustom(host: String) = change { it.copy(draft = it.draft.copy(customHost = host)) }
    fun save() {
        if (!state.value.canSave) return
        val value = state.value.value()
        state.value = state.value.copy(saving = true, error = null)
        job = viewModelScope.launch {
            try {
                repository.save(value); clearPeriodic(); clock.reset()
                state.value = state.value.copy(draft = value, saved = value,
                    measurement = null, notice = "Time settings saved. Synchronizing automatically.")
                nextAttemptNanos = clock.source.read().monotonicNanos
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = "Could not save time settings. Retry Save time settings.") }
            finally { state.value = state.value.copy(saving = false); poll() }
        }
    }
    fun measureNtp() { if (state.value.canMeasure) { clearPeriodic(); measure("NTP") { ntp.measure(state.value.saved!!.host) } } }
    fun requestGps(granted: Boolean): Boolean {
        if (!state.value.canEdit) return false
        if (!granted) { state.value = state.value.copy(awaitingGpsPermission = true); return true }
        clearPeriodic(); measure("GPS/GNSS") { gps.measure() }; return false
    }
    fun gpsPermissionResult(granted: Boolean) {
        if (!state.value.awaitingGpsPermission) return
        state.value = state.value.copy(awaitingGpsPermission = false)
        if (granted) requestGps(true) else state.value = state.value.copy(error = "Precise location permission is required for GPS time. Retry or allow it in Android app settings.")
    }
    private fun measure(name: String, operation: suspend () -> TimeMeasurement) {
        state.value = state.value.copy(busy = name, measurement = null, error = null, notice = null)
        job = viewModelScope.launch {
            try { state.value = state.value.copy(measurement = operation(), notice = "Measurement ready. Review it, then apply explicitly.") }
            catch (_: TimeoutCancellationException) { state.value = state.value.copy(error = if (name == "GPS/GNSS") "GPS timed out. Move outdoors with a clear sky view and retry." else "NTP timed out. Check the server/network and retry later.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.value = state.value.copy(error = when (error) {
                is java.net.UnknownHostException -> "Could not resolve the NTP hostname. Check spelling, DNS and the network."
                is java.io.IOException -> "NTP network request failed. Check the server/network and retry later."
                is SecurityException -> "GPS needs precise location permission. Allow it in Android app settings and retry."
                is IllegalStateException, is IllegalArgumentException -> error.message ?: "$name measurement failed. Retry."
                else -> "$name measurement failed. Retry."
            }) }
            finally {
                state.value = state.value.copy(busy = null)
                if (nextAttemptNanos == null) nextAttemptNanos = clock.source.read().monotonicNanos + RETRY_NANOS
                refresh()
            }
        }
    }
    fun applyMeasurement() {
        if (!state.value.canApply) return
        val value = state.value.measurement ?: return
        runCatching { clearPeriodic(); clock.apply(value) }.onSuccess {
            nextAttemptNanos = value.monotonicNanos + RETRY_NANOS
            state.value = state.value.copy(notice = "Measured time correction applied to this app only.", error = null) }
            .onFailure { state.value = state.value.copy(error = it.message ?: "Measure time again.") }
        refresh()
    }
    fun cancelManualMeasurement() {
        state.value = state.value.copy(awaitingGpsPermission = false)
        job?.takeIf { state.value.busy != null }?.cancel()
    }
    fun cancel() {
        cancelManualMeasurement()
        clearPeriodic(); nextAttemptNanos = clock.source.read().monotonicNanos + RETRY_NANOS
    }
    suspend fun resetForProfile() {
        val measurementJob = job?.takeIf { state.value.busy != null }
        state.value = state.value.copy(awaitingGpsPermission = false)
        measurementJob?.cancelAndJoin()
        clearPeriodic(); clock.reset()
        state.value = state.value.copy(measurement = null, ageMillis = null, notice = null)
        nextAttemptNanos = clock.source.read().monotonicNanos
        refresh()
    }
    companion object {
        private const val RETRY_NANOS = 600_000_000_000L
        fun factory(context: Context) = viewModelFactory {
            initializer { TimeViewModel(DataStoreTimePreferencesRepository(context.applicationContext.timePreferences),
                AndroidNtpClient(context), AndroidGpsTimeClient(context), ReceiveClock(AndroidTimeSource)) }
        }
    }
}

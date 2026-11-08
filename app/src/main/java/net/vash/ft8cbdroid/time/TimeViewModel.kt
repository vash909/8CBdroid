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
    private val gps: GpsTimeClient, val clock: ReceiveClock, private val tick: Boolean = true) : ViewModel() {
    private val state = MutableStateFlow(TimeUiState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    init {
        load()
        if (tick) viewModelScope.launch {
            while (isActive) {
                refresh(); delay(1000)
            }
        }
    }
    private fun refresh() {
        val sample = state.value.measurement
        val description = runCatching {
            val mapping = clock.snapshot()
            val age = 600 - ((mapping.expiresAtNanos!! - clock.source.read().monotonicNanos) / 1_000_000_000).coerceIn(0, 600)
            val quality = mapping.uncertaintyMillis?.let {
                java.lang.String.format(java.util.Locale.ROOT, "estimated uncertainty ±%.1f ms", it)
            } ?: "timing uncertainty not reported by receiver"
            "${mapping.source} • age $age s • $quality"
        }.getOrElse { it.message ?: "Time correction unavailable" }
        state.value = state.value.copy(sourceDescription = description, ageMillis = sample?.ageMillis(clock.source.read()))
    }
    fun load() {
        if (job?.isActive == true) return
        state.value = state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val value = repository.settings.first().validated()
                clock.reset()
                state.value = TimeUiState(draft = value, saved = value, loading = false)
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(loading = false, error = "Could not read time settings. Retry loading before editing.") }
        }
    }
    private fun change(block: (TimeUiState) -> TimeUiState) {
        if (state.value.canEdit) state.value = block(state.value).copy(measurement = null, ageMillis = null, error = null, notice = null)
    }
    fun selectPreset(id: String) = change { it.copy(draft = it.draft.copy(presetId = id)) }
    fun changeCustom(host: String) = change { it.copy(draft = it.draft.copy(customHost = host)) }
    fun save() {
        if (!state.value.canSave) return
        val value = state.value.value()
        state.value = state.value.copy(saving = true, error = null)
        job = viewModelScope.launch {
            try {
                repository.save(value); clock.reset()
                state.value = state.value.copy(draft = value, saved = value,
                    measurement = null, notice = "Time settings saved. Synchronize with NTP or GPS before assembling UTC slots.")
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = "Could not save time settings. Retry Save time settings.") }
            finally { state.value = state.value.copy(saving = false) }
        }
    }
    fun measureNtp() { if (state.value.canMeasure) measure("NTP") { ntp.measure(state.value.saved!!.host) } }
    fun requestGps(granted: Boolean): Boolean {
        if (!state.value.canEdit) return false
        if (!granted) { state.value = state.value.copy(awaitingGpsPermission = true); return true }
        measure("GPS/GNSS") { gps.measure() }; return false
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
            finally { state.value = state.value.copy(busy = null); refresh() }
        }
    }
    fun applyMeasurement() {
        if (!state.value.canApply) return
        val value = state.value.measurement ?: return
        runCatching { clock.apply(value) }.onSuccess { state.value = state.value.copy(notice = "Measured time correction applied to this app only.", error = null) }
            .onFailure { state.value = state.value.copy(error = it.message ?: "Measure time again.") }
        refresh()
    }
    fun cancel() { state.value = state.value.copy(awaitingGpsPermission = false); job?.takeIf { state.value.busy != null }?.cancel() }
    suspend fun resetForProfile() {
        val measurementJob = job?.takeIf { state.value.busy != null }
        state.value = state.value.copy(awaitingGpsPermission = false)
        measurementJob?.cancelAndJoin()
        clock.reset()
        state.value = state.value.copy(measurement = null, ageMillis = null, notice = null)
        refresh()
    }
    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { TimeViewModel(DataStoreTimePreferencesRepository(context.applicationContext.timePreferences),
                UdpNtpClient(AndroidTimeSource), AndroidGpsTimeClient(context), ReceiveClock(AndroidTimeSource)) }
        }
    }
}

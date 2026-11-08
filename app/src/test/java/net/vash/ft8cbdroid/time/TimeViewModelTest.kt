package net.vash.ft8cbdroid.time

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.time.*
import net.vash.ft8cbdroid.ntp.NtpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private class Repository : TimePreferencesRepository {
        var value = TimeSettings(); var readFailure = false; var writeFailure = false
        override val settings: Flow<TimeSettings> get() = flow { if (readFailure) throw IOException(); emit(value) }
        override suspend fun save(settings: TimeSettings) { if (writeFailure) throw IOException(); value = settings.validated() }
    }
    private val now = ClockReading(10_000_000_000, 1_800_000_000_000)
    private fun sample() = TimeMeasurement("NTP: test", now.monotonicNanos, now.unixMillis * 1_000_000 + 50_000_000, 2.0, now)
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun unreadSettingsCannotBeOverwrittenAndSaveFailureKeepsDraftForRetry() = runTest(dispatcher) {
        val repo = Repository().apply { readFailure = true }
        val vm = TimeViewModel(repo, NtpClient { sample() }, GpsTimeClient { sample() }, ReceiveClock(TimeSource { now }), false)
        runCurrent(); assertFalse(vm.uiState.value.canEdit); vm.selectPreset("custom"); assertEquals("global", vm.uiState.value.draft.presetId)
        repo.readFailure = false; vm.load(); runCurrent()
        vm.selectPreset("custom"); vm.changeCustom("NTP.Example.Org."); repo.writeFailure = true
        vm.save(); runCurrent(); assertNotNull(vm.uiState.value.error); assertEquals("global", repo.value.presetId)
        repo.writeFailure = false; vm.save(); runCurrent(); assertEquals("ntp.example.org", repo.value.host)
    }
    @Test fun measurementRequiresExplicitApplyAndDoesNotSurviveSettingsChangesOrNewProcess() = runTest(dispatcher) {
        val repo = Repository(); val clock = ReceiveClock(TimeSource { now })
        val vm = TimeViewModel(repo, NtpClient { sample() }, GpsTimeClient { sample() }, clock, false); runCurrent()
        vm.measureNtp(); runCurrent(); assertNotNull(vm.uiState.value.measurement)
        try { clock.snapshot(); fail() } catch (_: TimeException) { }
        vm.applyMeasurement(); assertEquals("NTP: test", clock.snapshot().source)
        vm.selectPreset("europe"); assertNull(vm.uiState.value.measurement); assertFalse(vm.uiState.value.canMeasure)
        vm.save(); runCurrent(); try { clock.snapshot(); fail() } catch (_: TimeException) { }
        val fresh = TimeViewModel(repo, NtpClient { sample() }, GpsTimeClient { sample() }, ReceiveClock(TimeSource { now }), false)
        runCurrent(); assertNull(fresh.uiState.value.measurement); assertEquals("europe", fresh.uiState.value.draft.presetId)
    }
    @Test fun lateGpsPermissionAndCancelledMeasurementsCannotActivateTime() = runTest(dispatcher) {
        var gpsCalls = 0
        val vm = TimeViewModel(Repository(), NtpClient { awaitCancellation() }, GpsTimeClient { gpsCalls++; sample() },
            ReceiveClock(TimeSource { now }), false); runCurrent()
        assertTrue(vm.requestGps(false)); vm.cancel(); vm.gpsPermissionResult(true); runCurrent(); assertEquals(0, gpsCalls)
        vm.requestGps(false); vm.gpsPermissionResult(false); assertNotNull(vm.uiState.value.error)
        vm.measureNtp(); runCurrent(); assertEquals("NTP", vm.uiState.value.busy)
        vm.cancel(); runCurrent(); assertNull(vm.uiState.value.busy); assertNull(vm.uiState.value.measurement)
    }
    @Test fun profileSwitchInvalidatesCorrectionMeasurementAndPendingGpsPermission() = runTest(dispatcher) {
        val clock = ReceiveClock(TimeSource { now })
        val vm = TimeViewModel(Repository(), NtpClient { sample() }, GpsTimeClient { sample() }, clock, false); runCurrent()
        vm.measureNtp(); runCurrent(); vm.applyMeasurement(); assertEquals("NTP: test",clock.snapshot().source)
        vm.requestGps(false); vm.resetForProfile(); vm.gpsPermissionResult(true); runCurrent()
        assertNull(vm.uiState.value.measurement); assertNull(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.awaitingGpsPermission); assertFalse(vm.uiState.value.canApply)
        assertTrue(runCatching { clock.snapshot() }.isFailure)
    }
    @Test fun gpsDoesNotDependOnAValidNtpDraftOrInternet() = runTest(dispatcher) {
        val clock = ReceiveClock(TimeSource { now })
        val vm = TimeViewModel(Repository(), NtpClient { error("GPS must not call NTP") },
            GpsTimeClient { sample().copy(source = "GPS/GNSS", uncertaintyMillis = null) }, clock, false); runCurrent()
        vm.selectPreset("custom"); vm.changeCustom("bad://host")
        assertFalse(vm.uiState.value.canMeasure); assertFalse(vm.uiState.value.canSave)
        vm.requestGps(true); runCurrent(); assertTrue(vm.uiState.value.canApply)
        vm.applyMeasurement(); assertEquals("GPS/GNSS", clock.snapshot().source)
        assertNull(clock.snapshot().uncertaintyMillis)
        assertTrue(vm.uiState.value.sourceDescription.contains("timing uncertainty not reported"))
    }
}

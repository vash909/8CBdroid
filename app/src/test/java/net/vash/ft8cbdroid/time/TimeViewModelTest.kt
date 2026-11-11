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
    private class MovingSource : TimeSource {
        var now = ClockReading(10_000_000_000L, 1_800_000_000_000L)
        override fun read() = now
        fun advance(ms: Long) { now = ClockReading(now.monotonicNanos + ms * 1_000_000, now.unixMillis + ms) }
        fun measured(host: String) = TimeMeasurement("NTP: $host", now.monotonicNanos, now.unixMillis * 1_000_000, 2.0, now)
    }
    @Test fun selectedNtpRefreshesAtFiveMinutesAndWaitsForAnIdleBoundary() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source); val repo = Repository(); var calls = 0
        val vm = TimeViewModel(repo, NtpClient { host -> calls++; source.measured(host) }, GpsTimeClient { error("Not GPS") }, clock, false)
        runCurrent(); vm.measureNtp(); runCurrent(); vm.applyMeasurement(); val revision = clock.revision
        var boundarySafe = true
        vm.idleBoundary { boundarySafe }
        vm.operating(true); source.advance(299_999); vm.poll(); runCurrent(); assertEquals(1, calls)
        source.advance(1); vm.poll(); runCurrent(); assertEquals(2, calls)
        assertTrue(vm.uiState.value.refreshPending); assertEquals(revision, clock.revision)
        source.advance(20_000); vm.poll(); runCurrent(); assertEquals(2, calls)
        boundarySafe = false; vm.operating(false)
        assertTrue(vm.uiState.value.refreshPending); assertEquals(revision, clock.revision)
        boundarySafe = true; vm.poll()
        assertFalse(vm.uiState.value.refreshPending); assertEquals(revision + 1, clock.revision)
        assertTrue(clock.acceptsRecovery(revision)); assertNull(vm.uiState.value.refreshWarning)
    }
    @Test fun failedRefreshDoesNotExtendExpiryOrRetryEverySlotAndRecoveryNeverStartsAudio() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source); var calls = 0; var fail = false
        val vm = TimeViewModel(Repository(), NtpClient { host -> calls++; if (fail) throw IOException(); source.measured(host) }, GpsTimeClient { error("Not GPS") }, clock, false)
        runCurrent(); vm.measureNtp(); runCurrent(); vm.applyMeasurement(); val expiry = clock.snapshot().expiresAtNanos
        fail = true; source.advance(300_000); vm.poll(); runCurrent()
        assertNotNull(vm.uiState.value.refreshWarning); assertEquals(expiry, clock.snapshot().expiresAtNanos)
        source.advance(15_000); vm.poll(); runCurrent(); assertEquals(2, calls)
        source.advance(285_000); fail = false; vm.poll(); runCurrent()
        assertEquals(3, calls); assertTrue(clock.snapshot().expiresAtNanos!! > expiry!!)
        assertFalse(vm.uiState.value.refreshPending); assertNull(vm.uiState.value.refreshWarning)
    }
    @Test fun gpsAndBackgroundDoNotStartPeriodicNetworkWork() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source); var calls = 0
        val vm = TimeViewModel(Repository(), NtpClient { host -> calls++; source.measured(host) },
            GpsTimeClient { source.measured("gps").copy(source = "GPS/GNSS") }, clock, false)
        runCurrent(); vm.requestGps(true); runCurrent(); vm.applyMeasurement(); source.advance(300_000); vm.poll(); runCurrent()
        assertEquals(0, calls); assertEquals("GPS/GNSS", clock.snapshot().source)
        source.advance(300_000); vm.poll(); runCurrent()
        assertEquals(0, calls); assertNotNull(vm.uiState.value.refreshWarning)
        vm.measureNtp(); runCurrent(); vm.applyMeasurement(); vm.foreground(false)
        source.advance(300_000); vm.poll(); runCurrent(); assertEquals(1, calls)
        vm.foreground(true); vm.poll(); runCurrent(); assertEquals(2, calls)
    }
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

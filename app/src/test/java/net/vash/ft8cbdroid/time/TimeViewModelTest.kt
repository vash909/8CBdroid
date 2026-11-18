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
    @Test fun startupAppliesTimeAndTenMinuteRefreshWaitsForAnIdleBoundary() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source); var calls = 0
        val vm = TimeViewModel(Repository(), NtpClient { host -> calls++; source.measured(host) }, GpsTimeClient { error("Not GPS") }, clock, false)
        runCurrent(); assertEquals(1, calls); val revision = clock.revision
        var boundarySafe = true; vm.idleBoundary { boundarySafe }
        vm.operating(true); source.advance(599_999); vm.poll(); runCurrent(); assertEquals(1, calls)
        source.advance(1); vm.poll(); runCurrent(); assertEquals(2, calls)
        assertTrue(vm.uiState.value.refreshPending); assertEquals(revision, clock.revision)
        source.advance(20_000); vm.poll(); runCurrent(); assertEquals(2, calls)
        boundarySafe = false; vm.operating(false); assertTrue(vm.uiState.value.refreshPending)
        boundarySafe = true; vm.poll()
        assertFalse(vm.uiState.value.refreshPending); assertEquals(revision + 1, clock.revision)
        assertTrue(clock.acceptsRecovery(revision)); assertNull(vm.uiState.value.refreshWarning)
    }
    @Test fun bothSourcesFailBeforeWarningAndHoldoverContinuesUntilTenMinuteRetrySucceeds() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source); var calls = 0; var gpsCalls = 0; var fail = false
        val vm = TimeViewModel(Repository(), NtpClient { host -> calls++; if (fail) throw IOException(); source.measured(host) },
            GpsTimeClient { gpsCalls++; throw IOException() }, clock, false)
        runCurrent(); val original = clock.snapshot(); assertEquals(1, calls)
        fail = true; source.advance(600_000); vm.poll(); runCurrent()
        assertNotNull(vm.uiState.value.refreshWarning); assertEquals(1, gpsCalls)
        assertEquals(original.revision, clock.snapshot().revision); assertNull(clock.snapshot().expiresAtNanos)
        source.advance(15_000); vm.poll(); runCurrent(); assertEquals(2, calls)
        source.advance(585_000); fail = false; vm.poll(); runCurrent()
        assertEquals(3, calls); assertTrue(clock.snapshot().measuredAtNanos > original.measuredAtNanos)
        assertFalse(vm.uiState.value.refreshPending); assertNull(vm.uiState.value.refreshWarning)
    }
    @Test fun preferredGpsStartupAndNtpFallbackDoNotWarnWhenEitherSucceeds() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source); var ntpCalls = 0; var gpsCalls = 0; var gpsFailed = false
        val repo = Repository().apply { value = TimeSettings(preferGps = true) }
        val vm = TimeViewModel(repo, NtpClient { host -> ntpCalls++; source.measured(host) },
            GpsTimeClient { gpsCalls++; if (gpsFailed) throw SecurityException(); source.measured("gps").copy(source = "GPS/GNSS") }, clock, false)
        runCurrent(); assertEquals("GPS/GNSS", clock.snapshot().source); assertEquals(0, ntpCalls)
        vm.foreground(false); source.advance(600_000); vm.poll(); runCurrent(); assertEquals(1, gpsCalls)
        gpsFailed = true; vm.foreground(true); vm.poll(); runCurrent()
        assertEquals(2, gpsCalls); assertEquals(1, ntpCalls); assertTrue(clock.snapshot().source.startsWith("NTP:"))
        assertNull(vm.uiState.value.refreshWarning)
    }
    @Test fun startupFallsBackToGpsAndNeitherSourceStartsAudioOrNeedsANtpSuccess() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source)
        val vm = TimeViewModel(Repository(), NtpClient { throw IOException() },
            GpsTimeClient { source.measured("gps").copy(source = "GPS/GNSS", uncertaintyMillis = null) }, clock, false)
        runCurrent(); assertEquals("GPS/GNSS", clock.snapshot().source); assertNull(vm.uiState.value.refreshWarning)
        assertFalse(vm.uiState.value.refreshPending)
    }
    @Test fun warningWaitsForTheSecondSourceAndLargeFreshCorrectionsNeedReviewWithoutAnOutageWarning() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source); val gpsResult = CompletableDeferred<TimeMeasurement>()
        val fallback = TimeViewModel(Repository(), NtpClient { throw IOException() }, GpsTimeClient { gpsResult.await() }, clock, false)
        runCurrent(); assertTrue(fallback.uiState.value.refreshing); assertNull(fallback.uiState.value.refreshWarning)
        gpsResult.completeExceptionally(IOException()); runCurrent(); assertNotNull(fallback.uiState.value.refreshWarning)
        var changed = false
        val reviewClock = ReceiveClock(source)
        val review = TimeViewModel(Repository(), NtpClient { host -> source.measured(host).let { if (changed) it.copy(utcNanos = it.utcNanos + 100_000_000) else it } },
            GpsTimeClient { error("NTP succeeds") }, reviewClock, false)
        runCurrent(); val revision = reviewClock.revision
        changed = true; source.advance(600_000); review.poll(); runCurrent()
        assertEquals(revision, reviewClock.revision); assertNull(review.uiState.value.refreshWarning)
        assertTrue(review.uiState.value.notice.orEmpty().contains("50 ms")); assertTrue(review.uiState.value.canApply)
    }
    @Test fun failedColdStartupRetriesAndCancellationRejectsLateAutomaticResults() = runTest(dispatcher) {
        val source = MovingSource(); var calls = 0; var fail = true; val clock = ReceiveClock(source)
        val vm = TimeViewModel(Repository(), NtpClient { host -> calls++; if (fail) throw IOException(); source.measured(host) },
            GpsTimeClient { throw SecurityException() }, clock, false)
        runCurrent(); assertNotNull(vm.uiState.value.refreshWarning); assertTrue(runCatching { clock.snapshot() }.isFailure)
        source.advance(599_999); vm.poll(); runCurrent(); assertEquals(1, calls)
        fail = false; source.advance(1); vm.poll(); runCurrent(); assertEquals(2, calls)
        assertTrue(clock.snapshot().source.startsWith("NTP:")); assertNull(vm.uiState.value.refreshWarning)
        val blocked = TimeViewModel(Repository(), NtpClient { awaitCancellation() }, GpsTimeClient { error("unused") }, ReceiveClock(source), false)
        runCurrent(); assertTrue(blocked.uiState.value.refreshing); blocked.cancel(); runCurrent(); assertFalse(blocked.uiState.value.refreshing)
    }
    @Test fun periodDisplayRetainsAppliedCorrectionAfterTenMinutesAndLabelsDeviceTimeOnlyAfterReset() = runTest(dispatcher) {
        val source = MovingSource(); val clock = ReceiveClock(source)
        val vm = TimeViewModel(Repository(), NtpClient { host -> source.measured(host).let { it.copy(utcNanos = it.utcNanos + 4_000_000_000L) } }, GpsTimeClient { error("Unused") }, clock, false, automatic = false)
        runCurrent(); vm.poll()
        assertFalse(vm.uiState.value.periodCorrected)
        assertEquals(Ft8PeriodProgress.atUtcMillis(source.now.unixMillis), vm.uiState.value.period)
        vm.measureNtp(); runCurrent(); vm.applyMeasurement(); vm.poll()
        assertTrue(vm.uiState.value.periodCorrected)
        assertEquals(Ft8PeriodProgress.atUtcMillis(source.now.unixMillis + 4000), vm.uiState.value.period)
        source.advance(15_000); vm.poll()
        assertEquals(Ft8PeriodProgress.atUtcMillis(source.now.unixMillis + 4000), vm.uiState.value.period)
        clock.reset(); vm.poll(); assertFalse(vm.uiState.value.periodCorrected)
        vm.measureNtp(); runCurrent(); vm.applyMeasurement(); source.advance(600_000); vm.poll()
        assertTrue(vm.uiState.value.periodCorrected)
        assertEquals(Ft8PeriodProgress.atUtcMillis(source.now.unixMillis + 4000), vm.uiState.value.period)
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
        val vm = TimeViewModel(repo, NtpClient { sample() }, GpsTimeClient { sample() }, ReceiveClock(TimeSource { now }), false, automatic = false)
        runCurrent(); assertFalse(vm.uiState.value.canEdit); vm.selectPreset("custom"); assertEquals("global", vm.uiState.value.draft.presetId)
        repo.readFailure = false; vm.load(); runCurrent()
        vm.selectPreset("custom"); vm.changeCustom("NTP.Example.Org."); repo.writeFailure = true
        vm.save(); runCurrent(); assertNotNull(vm.uiState.value.error); assertEquals("global", repo.value.presetId)
        repo.writeFailure = false; vm.save(); runCurrent(); assertEquals("ntp.example.org", repo.value.host)
    }
    @Test fun measurementRequiresExplicitApplyAndDoesNotSurviveSettingsChangesOrNewProcess() = runTest(dispatcher) {
        val repo = Repository(); val clock = ReceiveClock(TimeSource { now })
        val vm = TimeViewModel(repo, NtpClient { sample() }, GpsTimeClient { sample() }, clock, false, automatic = false); runCurrent()
        vm.measureNtp(); runCurrent(); assertNotNull(vm.uiState.value.measurement)
        try { clock.snapshot(); fail() } catch (_: TimeException) { }
        vm.applyMeasurement(); assertEquals("NTP: test", clock.snapshot().source)
        vm.selectPreset("europe"); assertNull(vm.uiState.value.measurement); assertFalse(vm.uiState.value.canMeasure)
        vm.save(); runCurrent(); try { clock.snapshot(); fail() } catch (_: TimeException) { }
        val fresh = TimeViewModel(repo, NtpClient { sample() }, GpsTimeClient { sample() }, ReceiveClock(TimeSource { now }), false, automatic = false)
        runCurrent(); assertNull(fresh.uiState.value.measurement); assertEquals("europe", fresh.uiState.value.draft.presetId)
    }
    @Test fun lateGpsPermissionAndCancelledMeasurementsCannotActivateTime() = runTest(dispatcher) {
        var gpsCalls = 0
        val vm = TimeViewModel(Repository(), NtpClient { awaitCancellation() }, GpsTimeClient { gpsCalls++; sample() },
            ReceiveClock(TimeSource { now }), false, automatic = false); runCurrent()
        assertTrue(vm.requestGps(false)); vm.cancel(); vm.gpsPermissionResult(true); runCurrent(); assertEquals(0, gpsCalls)
        vm.requestGps(false); vm.gpsPermissionResult(false); assertNotNull(vm.uiState.value.error)
        vm.measureNtp(); runCurrent(); assertEquals("NTP", vm.uiState.value.busy)
        vm.cancel(); runCurrent(); assertNull(vm.uiState.value.busy); assertNull(vm.uiState.value.measurement)
    }
    @Test fun profileSwitchInvalidatesCorrectionMeasurementAndPendingGpsPermission() = runTest(dispatcher) {
        val clock = ReceiveClock(TimeSource { now })
        val vm = TimeViewModel(Repository(), NtpClient { sample() }, GpsTimeClient { sample() }, clock, false, automatic = false); runCurrent()
        vm.measureNtp(); runCurrent(); vm.applyMeasurement(); assertEquals("NTP: test",clock.snapshot().source)
        vm.requestGps(false); vm.resetForProfile(); vm.gpsPermissionResult(true); runCurrent()
        assertNull(vm.uiState.value.measurement); assertNull(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.awaitingGpsPermission); assertFalse(vm.uiState.value.canApply)
        assertTrue(runCatching { clock.snapshot() }.isFailure)
    }
    @Test fun gpsDoesNotDependOnAValidNtpDraftOrInternet() = runTest(dispatcher) {
        val clock = ReceiveClock(TimeSource { now })
        val vm = TimeViewModel(Repository(), NtpClient { error("GPS must not call NTP") },
            GpsTimeClient { sample().copy(source = "GPS/GNSS", uncertaintyMillis = null) }, clock, false, automatic = false); runCurrent()
        vm.selectPreset("custom"); vm.changeCustom("bad://host")
        assertFalse(vm.uiState.value.canMeasure); assertFalse(vm.uiState.value.canSave)
        vm.requestGps(true); runCurrent(); assertTrue(vm.uiState.value.canApply)
        vm.applyMeasurement(); assertEquals("GPS/GNSS", clock.snapshot().source)
        assertNull(clock.snapshot().uncertaintyMillis)
        assertTrue(vm.uiState.value.sourceDescription.contains("timing uncertainty not reported"))
    }
}

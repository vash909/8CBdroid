package net.vash.ft8cbdroid.time

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.time.*
import net.vash.ft8cbdroid.ntp.NtpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedNtpClientTest {
    private var held = false
    private var releases = 0
    private fun acquire(): AutoCloseable {
        check(!held); held = true
        return AutoCloseable { check(held); held = false; releases++ }
    }
    @Test fun preparationPrecedesTimestampedRequestAndPreservesItsMeasurement() = runTest {
        val reading = ClockReading(1_000_000_000, 1_800_000_000_000)
        val measurement = TimeMeasurement("NTP: test", reading.monotonicNanos, reading.unixMillis * 1_000_000, 1.0, reading)
        var calls = 0
        val client = PreparedNtpClient(NtpClient {
            assertTrue(held); assertEquals(250L, testScheduler.currentTime); calls++; measurement
        }, ::acquire)
        assertSame(measurement, client.measure("test"))
        assertEquals(1, calls); assertEquals(1, releases); assertFalse(held)
    }
    @Test fun networkOrQualityRejectionReleasesResourcesWithoutHiddenRetries() = runTest {
        for (rejection in listOf(IOException("Network unavailable"), IllegalArgumentException("NTP uncertainty exceeds 100 ms"))) {
            var calls = 0
            val client = PreparedNtpClient(NtpClient { calls++; throw rejection }, ::acquire)
            val error = runCatching { client.measure("test") }.exceptionOrNull()
            assertEquals(rejection.javaClass, error?.javaClass)
            assertEquals(rejection.message, error?.message)
            assertEquals(1, calls); assertFalse(held)
        }
        assertEquals(2, releases)
    }
    @Test fun cancellationDuringPreparationReleasesBeforeAnyPacket() = runTest {
        var calls = 0
        val client = PreparedNtpClient(NtpClient { calls++; error("Unexpected packet") }, ::acquire)
        val job = launch { client.measure("test") }
        runCurrent(); assertTrue(held)
        job.cancelAndJoin()
        assertEquals(0, calls); assertEquals(1, releases); assertFalse(held)
    }
    @Test fun requestDeadlineAlsoReleasesAHungMeasurement() = runTest {
        val client = PreparedNtpClient(NtpClient { awaitCancellation() }, ::acquire)
        val result = async { runCatching { client.measure("test") } }
        runCurrent(); assertTrue(held)
        advanceTimeBy(8_001); runCurrent()
        assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
        assertEquals(1, releases); assertFalse(held)
    }
}

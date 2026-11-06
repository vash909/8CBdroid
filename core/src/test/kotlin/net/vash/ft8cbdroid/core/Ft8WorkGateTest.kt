package net.vash.ft8cbdroid.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Ft8WorkGateTest {
    @Test fun thirdRequestIsRejectedAndWaitersRunInOrderWithoutOverlapping() = runTest {
        val gate = Ft8WorkGate(); val firstDone = CompletableDeferred<Unit>(); val order = mutableListOf<Int>()
        var running = 0
        val first = launch { gate.run { assertEquals(0, running++); order += 1; firstDone.await(); running-- } }
        runCurrent()
        val second = launch { gate.run { assertEquals(0, running++); order += 2; running-- } }
        runCurrent(); assertEquals(2, gate.admittedCount)
        try { gate.run { fail("Third job started") }; fail("Capacity ignored") } catch (_: Ft8EngineBusyException) { }
        firstDone.complete(Unit); joinAll(first, second)
        assertEquals(listOf(1, 2), order); assertEquals(0, gate.admittedCount)
    }
    @Test fun cancellingWaiterReleasesAdmissionAndNeverExecutesItsBlock() = runTest {
        val gate = Ft8WorkGate(); val done = CompletableDeferred<Unit>()
        val first = launch { gate.run { done.await() } }; runCurrent()
        val cancelled = launch { gate.run { fail("Cancelled waiter ran") } }; runCurrent()
        cancelled.cancelAndJoin(); assertEquals(1, gate.admittedCount)
        val next = async { gate.run { 42 } }; runCurrent(); done.complete(Unit)
        assertEquals(42, next.await()); first.join(); assertEquals(0, gate.admittedCount)
    }
    @Test fun runningCleanupFinishesBeforeNextJobStartsAndExceptionsReleaseCapacity() = runTest {
        val gate = Ft8WorkGate(); var cleanup = false
        val first = launch { gate.run { try { awaitCancellation() } finally {
            withContext(NonCancellable) { delay(10); cleanup = true }
        } } }; runCurrent()
        val next = async { gate.run { assertTrue(cleanup); 7 } }; runCurrent()
        first.cancel(); assertEquals(2, gate.admittedCount); advanceUntilIdle()
        assertEquals(7, next.await()); assertEquals(0, gate.admittedCount)
        try { gate.run { error("failure") } } catch (_: IllegalStateException) { }
        assertEquals(0, gate.admittedCount); assertEquals(8, gate.run { 8 })
    }
    @Test fun alreadyCancelledCallerCannotTakeCapacity() = runTest {
        val gate = Ft8WorkGate()
        launch { currentCoroutineContext().cancel(); gate.run { fail("Cancelled caller ran") } }.join()
        assertEquals(0, gate.admittedCount)
    }
}

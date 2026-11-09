package net.vash.ft8cbdroid.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAudioCoordinatorTest {
    private class Receiver : PhoneAudioReceiver {
        var blocked = false
        var paused = false
        var resumes = 0
        var active = true
        var pauseDelay = 0L
        override fun blockStarts(blocked: Boolean) { this.blocked = blocked }
        override suspend fun pause(): (() -> Boolean)? {
            check(blocked)
            delay(pauseDelay)
            paused = true
            return if (!active) null else { { check(!blocked); resumes++; true } }
        }
    }
    @Test fun outputWaitsForInputCleanupAndRecoveryWaitsForOutputCleanupAndEchoGuard() = runTest {
        val receiver = Receiver().apply { pauseDelay = 200 }
        val coordinator = PhoneAudioCoordinator(receiver)
        var playing = false
        var resumed = false
        val run = launch {
            resumed = coordinator.output(true) {
                assertTrue(receiver.paused); assertTrue(receiver.blocked)
                playing = true
                try { delay(1000) } finally { playing = false; delay(100) }
            }
        }
        runCurrent(); assertEquals(PhoneAudioPhase.RELEASING_INPUT, coordinator.phase.value)
        advanceTimeBy(199); runCurrent(); assertFalse(playing)
        advanceTimeBy(1); runCurrent(); assertTrue(playing)
        advanceTimeBy(1100); runCurrent(); assertFalse(playing)
        assertEquals(PhoneAudioPhase.RECOVERING, coordinator.phase.value)
        advanceTimeBy(499); runCurrent(); assertEquals(0, receiver.resumes)
        advanceTimeBy(1); run.join()
        assertTrue(resumed); assertEquals(1, receiver.resumes)
        assertFalse(receiver.blocked); assertEquals(PhoneAudioPhase.IDLE, coordinator.phase.value)
    }
    @Test fun noConsentOrNoPreviouslyActiveReceiverNeverStartsInput() = runTest {
        for (consent in listOf(false, true)) for (active in listOf(false, true)) {
            val receiver = Receiver().apply { this.active = active }
            val coordinator = PhoneAudioCoordinator(receiver)
            val result = coordinator.output(consent) {}
            assertEquals(consent && active, result)
            assertEquals(if (consent && active) 1 else 0, receiver.resumes)
        }
    }
    @Test fun failuresAndCancellationDuringPreparationOutputOrRecoveryNeverResume() = runTest {
        for (stage in listOf(PhoneAudioPhase.RELEASING_INPUT, PhoneAudioPhase.OUTPUT, PhoneAudioPhase.RECOVERING)) {
            val receiver = Receiver().apply { if (stage == PhoneAudioPhase.RELEASING_INPUT) pauseDelay = 10_000 }
            val coordinator = PhoneAudioCoordinator(receiver)
            val run = launch { coordinator.output(true) { if (stage == PhoneAudioPhase.OUTPUT) awaitCancellation() } }
            runCurrent(); assertEquals(stage, coordinator.phase.value)
            run.cancelAndJoin(); advanceTimeBy(1000)
            assertEquals(0, receiver.resumes); assertFalse(receiver.blocked)
            assertEquals(PhoneAudioPhase.IDLE, coordinator.phase.value)
        }
        val receiver = Receiver(); val coordinator = PhoneAudioCoordinator(receiver)
        assertTrue(runCatching { coordinator.output(true) { throw PreviewException("Route lost") } }.exceptionOrNull() is PreviewException)
        assertEquals(0, receiver.resumes); assertFalse(receiver.blocked)
    }
    @Test fun simultaneousOutputIsRejectedRatherThanQueuedAndCleanupRetainsOwnership() = runTest {
        val receiver = Receiver(); val coordinator = PhoneAudioCoordinator(receiver)
        var cleaned = false
        val run = launch { coordinator.output(true) { try { awaitCancellation() } finally { withContext(NonCancellable) { delay(100); cleaned = true } } } }
        runCurrent(); run.cancel(); runCurrent()
        assertFalse(cleaned); assertTrue(receiver.blocked)
        assertTrue(runCatching { coordinator.output(true) { fail("Overlapping output") } }.isFailure)
        run.join(); assertTrue(cleaned); assertFalse(receiver.blocked)
        assertEquals(0, receiver.resumes)
    }
    @Test fun preparationHasAFiniteDeadlineAndDoesNotOpenOutputOnTimeout() = runTest {
        val receiver = Receiver().apply { pauseDelay = 16_000 }; val coordinator = PhoneAudioCoordinator(receiver)
        assertTrue(runCatching { coordinator.output(true) { fail("Opened output after timeout") } }.exceptionOrNull() is TimeoutCancellationException)
        assertFalse(receiver.blocked); assertEquals(0, receiver.resumes)
    }
}

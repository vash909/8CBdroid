package net.vash.ft8cbdroid.receive

import org.junit.Assert.*
import kotlinx.coroutines.test.runTest
import net.vash.ft8cbdroid.core.audio.*
import org.junit.Test

class ReceiveSessionTest {
    @Test fun receiveLeaseSurvivesTheUnblockedRecoveryWindow() = runTest {
        lateinit var coordinator: PhoneAudioCoordinator
        var inputActive = true
        var blocked = false
        var stopped = false
        var observedRecoveryGap = false
        coordinator = PhoneAudioCoordinator(object : PhoneAudioReceiver {
            override fun blockStarts(value: Boolean) {
                blocked = value
                if (!value && coordinator.phase.value == PhoneAudioPhase.RECOVERING) {
                    observedRecoveryGap = true
                    stopped = microphoneSessionEnded(!inputActive, blocked, coordinator.phase.value)
                }
            }
            override suspend fun pause(): (() -> Boolean) {
                inputActive = false
                return { inputActive = !stopped; !stopped }
            }
        })
        assertTrue(coordinator.output(resumeReceive = true) {})
        assertTrue(observedRecoveryGap); assertFalse(stopped); assertTrue(inputActive)
        assertFalse(microphoneSessionEnded(!inputActive, blocked, coordinator.phase.value))
        assertFalse(coordinator.output(resumeReceive = false) {})
        assertTrue(microphoneSessionEnded(!inputActive, blocked, coordinator.phase.value))
    }
    @Test fun repeatedStartsAndCrossSourceCommandsCannotCreateAnotherLease() {
        val owner = ReceiveSession()
        val first = requireNotNull(owner.request(ReceiveSource.MICROPHONE))
        assertNull(owner.request(ReceiveSource.MICROPHONE))
        assertNull(owner.request(ReceiveSource.LAN))
        owner.running(first)
        assertNull(owner.request(ReceiveSource.MICROPHONE))
        assertEquals(ReceiveSource.MICROPHONE, owner.uiState.value.source)
        assertEquals(ReceivePhase.RUNNING, owner.uiState.value.phase)
    }
    @Test fun stopInvalidatesUndeliveredServiceStartAndOldCompletion() {
        val owner = ReceiveSession()
        val first = requireNotNull(owner.request(ReceiveSource.MICROPHONE))
        val stop = owner.stopping()
        assertFalse(owner.accepts(first, ReceiveSource.MICROPHONE))
        owner.running(first)
        assertEquals(ReceivePhase.STOPPING, owner.uiState.value.phase)
        assertNull(owner.request(ReceiveSource.LAN))
        owner.stopped(stop)
        val second = requireNotNull(owner.request(ReceiveSource.LAN))
        owner.stopped(stop)
        owner.running(first)
        assertTrue(owner.accepts(second, ReceiveSource.LAN))
        assertFalse(owner.accepts(second, ReceiveSource.MICROPHONE))
    }
    @Test fun errorRequiresExplicitRetryAndNewProcessHasNoLease() {
        val owner = ReceiveSession()
        val first = requireNotNull(owner.request(ReceiveSource.MICROPHONE))
        owner.stopped(first, "Permission denied")
        assertFalse(owner.uiState.value.active)
        assertEquals("Permission denied", owner.uiState.value.error)
        assertFalse(owner.accepts(first, ReceiveSource.MICROPHONE))
        assertTrue(requireNotNull(owner.request(ReceiveSource.LAN)) > first)
        assertEquals(ReceiveSessionState(), ReceiveSession().uiState.value)
    }
}

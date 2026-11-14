package net.vash.ft8cbdroid.receive

import org.junit.Assert.*
import org.junit.Test

class OperatingSessionTest {
    private val mic = ReceiveSessionState(1, ReceiveSource.MICROPHONE, ReceivePhase.RUNNING)
    @Test fun explicitForegroundPromotionSurvivesBackgroundButNotAReplacementReceiver() {
        val session = OperatingSession(); var promotions = 0
        session.authorize(mic, true) { promotions++ }
        session.authorize(mic, false) { error("Already authorized") }
        assertTrue(session.authorized(mic)); assertEquals(1, promotions)
        val replacement = mic.copy(generation = 3)
        assertFalse(session.authorized(replacement))
        assertTrue(runCatching { session.authorize(replacement, false) {} }.isFailure)
        assertFalse(session.authorized(replacement))
    }
    @Test fun failedPromotionAndInvalidSourcesNeverAuthorizeOutput() {
        val session = OperatingSession()
        assertTrue(runCatching { session.authorize(mic, true) { error("Service promotion denied") } }.isFailure)
        assertFalse(session.authorized(mic))
        for (owner in listOf(mic.copy(source = ReceiveSource.LAN), mic.copy(phase = ReceivePhase.STARTING),
            mic.copy(phase = ReceivePhase.STOPPING), ReceiveSessionState())) {
            assertTrue(runCatching { session.authorize(owner, true) { error("Must not promote") } }.isFailure)
            assertFalse(session.authorized(owner))
        }
    }
    @Test fun stopAndFreshProcessRequireAnotherForegroundUserAction() {
        val session = OperatingSession(); session.authorize(mic, true) {}
        session.clear()
        assertFalse(session.authorized(mic))
        assertTrue(runCatching { session.authorize(mic, false) {} }.isFailure)
        assertFalse(OperatingSession().authorized(mic))
    }
}

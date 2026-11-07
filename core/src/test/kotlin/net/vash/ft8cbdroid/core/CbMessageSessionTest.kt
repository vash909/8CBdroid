package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.message.*
import org.junit.Assert.*
import org.junit.Test

class CbMessageSessionTest {
    private fun payload(text: String) = (CbMessageCodec.pack(text) as PackResult.Encoded).payload
    private fun receive(session: CbMessageSession, text: String) =
        (session.unpack(payload(text)) as UnpackResult.Decoded).message
    private fun hash(call: String, width: Int = 12) = CbCallsignHash.calculate(call, width)

    @Test fun coldPendingCommittedAndResetAreSeparateStates() {
        val session = CbMessageSession()
        assertEquals("<...> 26AT101", receive(session, "<1AT106> 26AT101").text)
        receive(session, "CQ 1AT106")
        assertTrue(session.lookup(hash("1AT106")) is MessagePart.UnresolvedCall)
        session.commitPending()
        val snapshot = receive(session, "<1AT106> 26AT101")
        assertEquals("<1AT106> 26AT101", snapshot.text)
        session.reset()
        assertEquals(0, session.learnedCount)
        assertEquals(0, session.pendingCount)
        assertEquals("<...> 26AT101", receive(session, "<1AT106> 26AT101").text)
        assertEquals("<1AT106> 26AT101", snapshot.text)
    }

    @Test fun sessionsAndTxPreviewsCannotTeachEachOther() {
        val first = CbMessageSession()
        first.learnCall("1AT106")
        first.commitPending()
        val other = CbMessageSession()
        repeat(3) { CbMessageCodec.pack("<1AT106> 26AT101") }
        assertTrue(other.lookup(hash("1AT106")) is MessagePart.UnresolvedCall)
        assertEquals("<...> 26AT101", (CbMessageCodec.unpack(payload("<1AT106> 26AT101")) as UnpackResult.Decoded).message.text)
        assertEquals("<1AT106> 26AT101", receive(first, "<1AT106> 26AT101").text)
    }

    @Test fun freeTextAndUnsupportedFramesNeverTeachCalls() {
        val session = CbMessageSession()
        receive(session, "26AT101 R-10")
        receive(session, "1AT106 26AT101 -10")
        session.unpack(Ft8Payload77.fromBits("0".repeat(74) + "110"))
        assertEquals(0, session.pendingCount)
        session.commitPending()
        assertEquals(0, session.learnedCount)
        for (call in listOf("<...>", "<1AT106>", "CQ", "RR73 -10", "１AT106", "1ß1", "1AT106\t", "1".repeat(12)))
            assertEquals(call, LearnResult.INVALID, session.learnCall(call))
    }

    @Test fun collisionCandidatesStayAmbiguousUntilResetAtEveryWidth() {
        for ((width, calls) in listOf(10 to setOf("1AT0002", "1AT0050"),
            12 to setOf("1AT0002", "1AT0050"), 22 to setOf("5AT179", "10AT107"))) {
            val session = CbMessageSession()
            calls.forEach { session.learnCall(it); session.commitPending() }
            val key = hash(calls.first(), width)
            val ambiguous = session.lookup(key) as MessagePart.AmbiguousCall
            assertEquals(calls, ambiguous.candidates)
            session.learnCall(calls.first())
            session.commitPending()
            assertEquals(ambiguous, session.lookup(key))
            if (width == 12) assertTrue(session.lookup(hash(calls.first(), 22)) is MessagePart.ResolvedCall)
            session.reset()
            assertTrue(session.lookup(key) is MessagePart.UnresolvedCall)
        }
    }

    @Test fun stationOrientationAndLocalCollisionsRemainExplicit() {
        val session = CbMessageSession("1AT106")
        assertEquals("<1AT106> 26AT101", receive(session, "<1AT106> 26AT101").text)
        assertEquals("26AT101 <...>", receive(session, "26AT101 <1AT106>").text)
        assertTrue(session.lookup(hash("1AT106")) is MessagePart.UnresolvedCall)
        assertTrue(session.lookup(hash("1AT106", 22)) is MessagePart.ResolvedCall)
        session.reset("1AT0002")
        session.learnCall("1AT0050")
        session.commitPending()
        val leading = receive(session, "<1AT0002> 26AT101").parts[0] as MessagePart.AmbiguousCall
        assertEquals(setOf("1AT0002", "1AT0050"), leading.candidates)
        session.reset("26AT101")
        assertTrue(session.lookup(hash("1AT0002", 22)) is MessagePart.UnresolvedCall)
    }

    @Test fun rejectedFrameCannotPartiallyWarmTheSession() {
        val session = CbMessageSession()
        val bits = "00000100000101110010110101110000010011011110111100011010110000010011010010001"
        assertTrue(session.unpack(Ft8Payload77.fromBits(bits)) is UnpackResult.Unsupported)
        assertEquals(0, session.pendingCount)
    }

    @Test fun capacityLimitsFailClosedAndNeverForgetCollisions() {
        for (session in listOf(CbMessageSession(capacity = 1), CbMessageSession(pendingCapacity = 1))) {
            assertEquals(LearnResult.QUEUED, session.learnCall("1AT0002"))
            assertEquals(LearnResult.KNOWN, session.learnCall("1AT0002"))
            assertEquals(LearnResult.CAPACITY_REACHED, session.learnCall("1AT0050"))
            session.commitPending()
            assertTrue(session.capacityReached)
            assertEquals(UnresolvedReason.CAPACITY_REACHED,
                (session.lookup(hash("1AT0002")) as MessagePart.UnresolvedCall).reason)
            session.reset()
            assertFalse(session.capacityReached)
            assertEquals(LearnResult.QUEUED, session.learnCall("26AT101"))
        }
    }

    @Test fun normalizationIsAsciiAndResetValidationDoesNotDestroyState() {
        val session = CbMessageSession()
        assertEquals(LearnResult.QUEUED, session.learnCall(" 1at106 "))
        session.commitPending()
        assertTrue(runCatching { session.reset("<...>") }.isFailure)
        assertEquals(MessagePart.ResolvedCall(hash("1AT106"), "1AT106"), session.lookup(hash("1AT106")))
    }
}

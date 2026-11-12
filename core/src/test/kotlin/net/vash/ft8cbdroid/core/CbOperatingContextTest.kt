package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.context.*
import net.vash.ft8cbdroid.core.message.*
import org.junit.Assert.*
import org.junit.Test

class CbOperatingContextTest {
    private fun text(v: String) = MessagePart.Text(v)
    private fun resolved(v: String) = MessagePart.ResolvedCall(CbCallsignHash.calculate(v, 12), v)
    private fun message(format: MessageFormat, vararg parts: MessagePart) = DecodedMessage(format, parts.toList())

    @Test fun cqDirectedAndUnknownCountryKeepTheirMeaning() {
        val cq = CbOperatingContext.fromDesktopText("CQ 26AT101")
        assertEquals("England", cq.division?.countryName); assertEquals("26AT101", cq.sender)
        assertTrue(cq.matches(ActivityFilter.CQ, "1AT106")); assertFalse(cq.matches(ActivityFilter.TO_ME, "1AT106"))
        val reply = CbOperatingContext.fromDesktopText("<1AT106> 26AT101")
        assertEquals("26AT101", reply.sender); assertEquals("1AT106", reply.recipient)
        assertTrue(reply.matches(ActivityFilter.TO_ME, " 1at106 "))
        assertFalse(reply.matches(ActivityFilter.TO_ME, "161AT101"))
        assertEquals("999", CbOperatingContext.fromDesktopText("CQ 999ZZ999").division?.prefix)
        assertNull(CbOperatingContext.fromDesktopText("CQ 999ZZ999").division?.countryName)
    }
    @Test fun trailingHashTypeFourDoesNotShowTheDestinationCountryAsTheSender() {
        val context = CbOperatingContext.fromMessage(message(MessageFormat.NONSTANDARD_CALL, text("26AT101"), resolved("1AT106"), text("RR73")))
        assertEquals("26AT101", context.sender); assertEquals("1AT106", context.recipient)
        assertEquals("England", context.division?.countryName)
        assertTrue(context.matches(ActivityFilter.TO_ME, "1AT106"))
        assertEquals(context, CbOperatingContext.fromDesktopText("26AT101 <1AT106> RR73"))
    }
    @Test fun destinationOnlyReportsDoNotInventAnIdentityFromTheSelectedPeerOrOwnCall() {
        for (line in listOf("1AT106 -07", "1AT106 R-07", "1AT106 RR73", "1AT106 73")) {
            val value = CbOperatingContext.fromDesktopText(line)
            assertEquals(ActivityKind.DIRECTED, value.kind)
            assertTrue(value.matches(ActivityFilter.TO_ME, "1AT106"))
            assertNull(value.sender); assertNull(value.division)
        }
    }
    @Test fun unresolvedAndAmbiguousIdentitiesAreNotGuessed() {
        val unknown = MessagePart.UnresolvedCall(CallsignHash(12, 0))
        val ambiguous = MessagePart.AmbiguousCall(CallsignHash(12, 0), setOf("26AT101", "161AT101"))
        for (identity in listOf(unknown, ambiguous)) {
            val cq = CbOperatingContext.fromMessage(message(MessageFormat.NONSTANDARD_CALL, text("CQ"), identity))
            assertTrue(cq.matches(ActivityFilter.CQ, "1AT106")); assertNull(cq.sender); assertNull(cq.division)
            val directed = CbOperatingContext.fromMessage(message(MessageFormat.STANDARD, text("1AT106"), identity, text("RR73")))
            assertTrue(directed.matches(ActivityFilter.TO_ME, "1AT106")); assertNull(directed.sender)
        }
        assertEquals(ActivityKind.OTHER, CbOperatingContext.fromDesktopText("CQ <...>").kind)
    }
    @Test fun arbitraryFreeTextAndLossyDesktopInputsRemainUnclassified() {
        for (line in listOf("HELLO WORLD", "CQ 26AT101 EXTRA", "26AT101", "", "<...> 26AT101", "A".repeat(100))) {
            val value = CbOperatingContext.fromDesktopText(line)
            assertEquals(line, ActivityKind.OTHER, value.kind)
            assertNull(value.sender); assertTrue(value.matches(ActivityFilter.ALL, "1AT106"))
        }
        val plain = CbOperatingContext.fromMessage(message(MessageFormat.FREE_TEXT, text("CQ 26AT101")))
        assertEquals(ActivityKind.OTHER, plain.kind)
        assertFalse(plain.matches(ActivityFilter.TO_ME, ""))
    }
    @Test fun filteringOnlyProjectsTheDisplayAndPreservesBackendInputRows() {
        val source = listOf("CQ 26AT101", "<1AT106> 26AT101", "CQ 999ZZ999", "HELLO WORLD")
        val contexts = source.map(CbOperatingContext::fromDesktopText)
        assertEquals(2, contexts.count { it.matches(ActivityFilter.CQ, "1AT106") })
        assertEquals(1, contexts.count { it.matches(ActivityFilter.TO_ME, "1AT106") })
        assertEquals(4, contexts.count { it.matches(ActivityFilter.ALL, "1AT106") })
        assertEquals(4, source.size)
    }
}

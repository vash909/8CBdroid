package net.vash.ft8cbdroid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CbCallsignTest {
    @Test fun acceptsUpstreamCbExamples() {
        listOf("1A1", "26AT101", "999ZZ999", "1AT1000", "999ZZ/ZZ", " 1at106 ").forEach {
            assertTrue(it, CbCallsign.isValid(it))
        }
    }

    @Test fun rejectsIncompleteAndOversizedCalls() {
        listOf("", "1AT", "26AT1000", "111TT1000", "1TT10000", "12ABC1", "1/AT100", "<...>").forEach {
            assertFalse(it, CbCallsign.isValid(it))
        }
    }
}

package net.vash.ft8cbdroid.core

import org.junit.Assert.*
import org.junit.Test

class MaidenheadLocatorTest {
    @Test fun acceptsFourAndSixCharacterLocators() {
        listOf("JN45", "AA00AA", "RR99XX", " jn45ab ").forEach {
            assertTrue(it, MaidenheadLocator.isValid(it))
        }
    }

    @Test fun rejectsMissingAndOutOfRangeFields() {
        listOf("", "JN", "JN4", "SS45", "JN4A", "JN45AY", "JN45A", "JN45AB12").forEach {
            assertFalse(it, MaidenheadLocator.isValid(it))
        }
    }

    @Test fun normalizesBothStationFields() {
        assertEquals(StationIdentity("1AT106", "JN45AB"), StationIdentity(" 1at106 ", " jn45ab ").normalized())
    }
}

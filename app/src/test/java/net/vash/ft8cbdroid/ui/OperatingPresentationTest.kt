package net.vash.ft8cbdroid.ui

import net.vash.ft8cbdroid.core.context.*
import net.vash.ft8cbdroid.audio.CaptureUiState
import net.vash.ft8cbdroid.lan.LanUiState
import net.vash.ft8cbdroid.protocol.*
import org.junit.Assert.*
import org.junit.Test

class OperatingPresentationTest {
    @Test fun invalidFrequencyEditsCannotPublishOutOfRangeCursors() {
        for (value in listOf("", "0", "199", "2951", "3000", "-1", "12.5", "abcd")) assertNull(audioOffset(value))
        assertEquals(200, audioOffset("200")); assertEquals(1000, audioOffset("1000")); assertEquals(2950, audioOffset("2950"))
    }
    @Test fun desktopMessagesKeepProvenanceAndNeverAppearAsLocalAudio() {
        val first = LanDecode(true, 3600123, -12, .3, 1000, "FT4", "CQ 1AT106")
        val replay = first.copy(isNew = false, message = "<1AT106> 26AT101", lowConfidence = true, offAir = true)
        val state = LanUiState(connection = LanState(decodes = listOf(first, replay)))
        val rows = activityMessages(OperatingSource.LAN, CaptureUiState(), state)
        assertEquals(listOf("<1AT106> 26AT101", "CQ 1AT106"), rows.map { it.message })
        assertEquals("England", rows.first().context.division?.countryName)
        assertEquals("Italy", rows.last().context.division?.countryName)
        assertNull(rows.first().peer)
        assertEquals(1, rows.count { it.context.matches(ActivityFilter.CQ, "1AT106") })
        assertEquals(1, rows.count { it.context.matches(ActivityFilter.TO_ME, "1AT106") })
        assertEquals("01:00:00", rows.first().time)
        assertEquals("-12", rows.first().signal)
        assertTrue(rows.first().detail.contains("replay")); assertTrue(rows.first().detail.contains("Off air"))
        assertTrue(rows.first().detail.contains("Low confidence")); assertTrue(rows.first().detail.contains("FT4"))
        assertTrue(activityMessages(OperatingSource.LOCAL, CaptureUiState(), state).isEmpty())
    }
    @Test fun countryLabelsNeverMisrepresentDestinationOnlyMessagesAsSenders() {
        assertEquals("", countryLabel(CbOperatingContext.fromDesktopText("1AT106 RR73")))
        assertEquals("From 26AT101 · CB 026 · England", countryLabel(CbOperatingContext.fromDesktopText("CQ 26AT101")))
        assertEquals("From 999ZZ999 · CB 999 · Country unknown", countryLabel(CbOperatingContext.fromDesktopText("CQ 999ZZ999")))
        assertEquals("From 165AT101 · CB 165 · Sardinia", countryLabel(CbOperatingContext.fromDesktopText("CQ 165AT101")))
    }
}

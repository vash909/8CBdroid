package net.vash.ft8cbdroid.ui

import net.vash.ft8cbdroid.audio.CaptureUiState
import net.vash.ft8cbdroid.lan.LanUiState
import net.vash.ft8cbdroid.protocol.*
import org.junit.Assert.*
import org.junit.Test

class OperatingPresentationTest {
    @Test fun receiverViewsKeepCaptureButConfigurationAndChatRequireCleanup() {
        assertFalse(stopsLocalReception(AppDestination.OPERATE, AppDestination.CAPTURE))
        assertFalse(stopsLocalReception(AppDestination.CAPTURE, AppDestination.OPERATE))
        for (to in listOf(AppDestination.SETTINGS, AppDestination.STATION, AppDestination.TIME,
            AppDestination.AUDIO, AppDestination.LAN, AppDestination.USB, AppDestination.MESSAGES,
            AppDestination.RECORDINGS, AppDestination.CHAT)) {
            assertTrue(stopsLocalReception(AppDestination.OPERATE, to))
            assertTrue(stopsLocalReception(AppDestination.CAPTURE, to))
        }
        assertFalse(stopsLocalReception(AppDestination.OPERATE, AppDestination.OPERATE))
    }
    @Test fun invalidFrequencyEditsCannotPublishOutOfRangeCursors() {
        for (value in listOf("", "0", "199", "2951", "3000", "-1", "12.5", "abcd")) assertNull(audioOffset(value))
        assertEquals(200, audioOffset("200")); assertEquals(1000, audioOffset("1000")); assertEquals(2950, audioOffset("2950"))
    }
    @Test fun desktopMessagesKeepProvenanceAndNeverAppearAsLocalAudio() {
        val first = LanDecode(true, 3600123, -12, .3, 1000, "FT8", "CQ 1AT106")
        val replay = first.copy(isNew = false, message = "<1AT106> 26AT101", lowConfidence = true, offAir = true)
        val state = LanUiState(connection = LanState(decodes = listOf(first, replay)))
        val rows = activityMessages(OperatingSource.LAN, CaptureUiState(), state)
        assertEquals(listOf("<1AT106> 26AT101", "CQ 1AT106"), rows.map { it.message })
        assertEquals("01:00:00", rows.first().time)
        assertEquals("-12", rows.first().signal)
        assertTrue(rows.first().detail.contains("replay")); assertTrue(rows.first().detail.contains("Off air"))
        assertTrue(rows.first().detail.contains("Low confidence"))
        assertTrue(activityMessages(OperatingSource.LOCAL, CaptureUiState(), state).isEmpty())
    }
}

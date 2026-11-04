package net.vash.ft8cbdroid.lan

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodePresentationTest {
    @Test fun formatsUtcTimeWithoutUsingThePhoneLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("13:47:15.250 UTC", formatDecodeTime(49_635_250))
            assertEquals("00:00:00.000 UTC", formatDecodeTime(0))
            assertEquals("23:59:59.999 UTC", formatDecodeTime(86_399_999))
        } finally { Locale.setDefault(original) }
    }

    @Test fun labelsMissingQtTimeInEnglish() {
        assertEquals("UTC time unavailable", formatDecodeTime(null))
    }
}

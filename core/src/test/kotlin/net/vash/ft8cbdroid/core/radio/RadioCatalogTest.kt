package net.vash.ft8cbdroid.core.radio

import org.junit.Assert.*
import org.junit.Test

class RadioCatalogTest {
    private fun model(id: Int = 3073) = RadioModel(id, "Icom", "IC-7300", "Stable", 1, 300, 115200, true, true, false)
    @Test fun searchKeepsStableIdentityAndUsesCaseInsensitiveNames() {
        val model = model()
        assertTrue(model.matches(" icom "))
        assertTrue(model.matches("7300"))
        assertTrue(model.matches("3073"))
        assertFalse(model.matches("307"))
        assertFalse(model.matches("Yaesu"))
        assertFalse(model.isDummy)
        assertTrue(model(1).isDummy)
    }
    @Test fun catalogRejectsDuplicateIdentitiesAndMissingModels() {
        assertThrows(IllegalArgumentException::class.java) { RadioCatalogSnapshot("4.7.2", listOf(model(), model())) }
        assertThrows(IllegalArgumentException::class.java) { RadioCatalogSnapshot("4.7.2", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { model(0) }
    }
    @Test fun dummyMustMatchTheEntireExpectedSoftwareState() {
        assertTrue(DummyRadioCheck(27_265_000, "USB", true).passed)
        assertFalse(DummyRadioCheck(14_074_000, "USB", true).passed)
        assertFalse(DummyRadioCheck(27_265_000, "LSB", true).passed)
        assertFalse(DummyRadioCheck(27_265_000, "USB", false).passed)
    }
}

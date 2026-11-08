package net.vash.ft8cbdroid.core.profile

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import net.vash.ft8cbdroid.core.ConnectionMode
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.core.radio.*
import org.junit.Assert.*
import org.junit.Test

class OperatingProfileTest {
    private val model = RadioModel(3073, "Icom", "IC-7300", "Stable", 1, 300, 19200, true, true, true)
    private val catalog = RadioCatalogSnapshot("Hamlib 4.7.2", listOf(model))
    private val usb = OperatingProfile(id = "usb", name = "USB setup", mode = ConnectionMode.USB_CAT,
        radio = HamlibModelRef(3073, catalog.version, "Icom IC-7300"),
        serial = SerialSettings(19200, 7, 2, SerialParity.EVEN, SerialFlow.RTS_CTS),
        audioInput = ProfileAudioRoute.USB, audioOutput = ProfileAudioRoute.WIRED,
        lan = ProfileLan(2240, "192.0.2.4"), decoder = Ft8DecodeProfile.FAST)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid profile data to be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun allConfigurationFieldsSurviveTheVersionedFile() {
        val value = ProfileCollection(listOf(OperatingProfile(), usb), "usb")
        assertEquals(value, ProfileCodec.decode(ProfileCodec.encode(value)))
        assertEquals(value, ProfileCodec.read(ByteArrayInputStream(ProfileCodec.encode(value))))
        assertEquals(usb, value.active)
    }
    @Test fun missingCatModelIsRejectedButPhoneAndLanDoNotRequireOne() {
        rejected { OperatingProfile(mode = ConnectionMode.USB_CAT).validate() }
        OperatingProfile().validate(); OperatingProfile(mode = ConnectionMode.WSJT_CB_LAN).validate()
    }
    @Test fun catalogValidationRejectsRemappedVersionMissingModelDummyNetworkAndOutOfRangeBaud() {
        usb.validateCatalog(catalog)
        rejected { usb.copy(radio = usb.radio!!.copy(version = "Hamlib 5.0")).validateCatalog(catalog) }
        rejected { usb.copy(radio = usb.radio!!.copy(id = 99)).validateCatalog(catalog) }
        rejected { usb.copy(radio = usb.radio!!.copy(id = 1)).validate() }
        rejected { usb.validateCatalog(catalog.copy(models = listOf(model.copy(portType = 2)))) }
        rejected { usb.copy(serial = SerialSettings(38400)).validateCatalog(catalog) }
    }
    @Test fun validationRejectsInvalidNamesFrequenciesAndSerialSettings() {
        listOf("", " A", "a\nb", "x".repeat(49)).forEach { rejected { usb.copy(name = it).validate() } }
        listOf(0L, -1L, 1_000_000_000L).forEach { rejected { usb.copy(referenceFrequencyHz = it).validate() } }
        listOf(SerialSettings(0), SerialSettings(dataBits = 9), SerialSettings(stopBits = 0)).forEach { rejected { usb.copy(serial = it).validate() } }
    }
    @Test fun lanValidatesLiteralIpv4AndPortWithoutResolvingNames() {
        listOf("", "192.0.2.1", "001.2.3.4").forEach { ProfileLan(2237, it).validate() }
        listOf("example.com", "::1", "1.2.3.256", "1.2.3", "1.2.3.-1", " 1.2.3.4").forEach { rejected { ProfileLan(2237, it).validate() } }
        listOf(0, 65536).forEach { rejected { ProfileLan(it).validate() } }
    }
    @Test fun collectionProtectsActiveProfileNamesAndBoundedCount() {
        val value = ProfileCollection().save(usb)
        rejected { value.delete("default") }
        rejected { value.save(usb.copy(id = "duplicate", name = "DEFAULT")) }
        rejected { value.copy(activeId = "missing").validate() }
        rejected { ProfileCollection(List(33) { OperatingProfile(id = "p$it", name = "P$it") }, "p0").validate() }
        assertEquals(ProfileCollection(), value.delete("usb"))
    }
    @Test fun corruptTruncatedFutureAndTrailingDataAreRejected() {
        val bytes = ProfileCodec.encode(ProfileCollection())
        listOf(0, 1, 7, bytes.size - 1).forEach { n ->
            assertTrue(runCatching { ProfileCodec.decode(bytes.copyOf(n)) }.isFailure)
        }
        rejected { ProfileCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 999) }) }
        rejected { ProfileCodec.decode(bytes + byteArrayOf(0)) }
        rejected { ProfileCodec.decode(ByteArray(65537)) }
        rejected { ProfileCodec.read(ByteArrayInputStream(ByteArray(65537))) }
    }
    @Test fun decodingRejectsOversizedCountBeforeAllocatingAList() {
        val bytes = ProfileCodec.encode(ProfileCollection())
        // magic/version + UTF length/default ID, then the collection count.
        ByteBuffer.wrap(bytes).putInt(8 + 2 + "default".length, Int.MAX_VALUE)
        rejected { ProfileCodec.decode(bytes) }
    }
}

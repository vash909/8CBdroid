package net.vash.ft8cbdroid.core.profile

import net.vash.ft8cbdroid.core.ConnectionMode
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.core.radio.RadioCatalogSnapshot

/** Stable preferences only: never store Android device IDs, connections, PTT or clock measurements. */
enum class ProfileAudioRoute(val label: String) { PHONE("Phone"), USB("USB audio"), WIRED("Wired headset / line") }
enum class SerialParity { NONE, EVEN, ODD }
enum class SerialFlow { NONE, RTS_CTS, XON_XOFF }
data class SerialSettings(val baud: Int = 9600, val dataBits: Int = 8, val stopBits: Int = 1,
    val parity: SerialParity = SerialParity.NONE, val flow: SerialFlow = SerialFlow.NONE) {
    fun validate() {
        require(baud in 300..4_000_000) { "Serial rate must be between 300 and 4000000 baud." }
        require(dataBits in 5..8 && stopBits in 1..2) { "Choose 5–8 data bits and 1–2 stop bits." }
    }
}
data class HamlibModelRef(val id: Int, val version: String, val label: String)
data class ProfileLan(val port: Int = 2237, val desktopAddress: String = "") {
    fun validate() {
        require(port in 1..65535) { "UDP port must be between 1 and 65535." }
        require(validAddress(desktopAddress)) { "Desktop address must be empty or an IPv4 address." }
    }
    companion object {
        fun validAddress(value: String): Boolean = value.isEmpty() || value.split('.').let { parts ->
            parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } && (it.toIntOrNull() ?: -1) in 0..255 }
        }
    }
}
data class OperatingProfile(
    val id: String = "default", val name: String = "Default",
    val mode: ConnectionMode = ConnectionMode.PHONE_VOX,
    val referenceFrequencyHz: Long = 27_265_000L,
    val radio: HamlibModelRef? = null, val serial: SerialSettings = SerialSettings(),
    val audioInput: ProfileAudioRoute = ProfileAudioRoute.PHONE,
    val audioOutput: ProfileAudioRoute = ProfileAudioRoute.PHONE,
    val lan: ProfileLan = ProfileLan(), val decoder: Ft8DecodeProfile = Ft8DecodeProfile.STANDARD,
) {
    fun validate(): OperatingProfile {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid profile identifier." }
        require(name == name.trim() && name.length in 1..48 && name.none { it.isISOControl() }) { "Use a profile name of 1–48 characters." }
        require(referenceFrequencyHz in 1..999_999_999) { "Reference dial must be between 1 and 999999999 Hz." }
        serial.validate(); lan.validate()
        radio?.let {
            require(it.id > 1 && it.version.length in 1..80 && it.label.length in 1..160) { "Choose a real Hamlib model and version." }
        }
        require(mode != ConnectionMode.USB_CAT || radio != null) { "Choose a Hamlib model for USB CAT." }
        return this
    }
    /** A backend upgrade must never silently remap a model or serial setting. */
    fun validateCatalog(catalog: RadioCatalogSnapshot) {
        if (mode != ConnectionMode.USB_CAT) return
        val ref = requireNotNull(radio)
        require(ref.version == catalog.version) { "Hamlib version changed. Load models and reselect the radio before applying." }
        val model = catalog.models.singleOrNull { it.id == ref.id }
        require(model != null && !model.isDummy && model.portType == 1) { "Saved model is unavailable as a serial radio. Reselect a model." }
        require(serial.baud in model.baudMin..model.baudMax) { "Serial rate must be within the model's declared ${model.baudMin}–${model.baudMax} baud range." }
    }
}

data class ProfileCollection(val profiles: List<OperatingProfile> = listOf(OperatingProfile()), val activeId: String = "default") {
    val active get() = profiles.single { it.id == activeId }
    fun validate(): ProfileCollection {
        require(profiles.size in 1..32) { "Keep between 1 and 32 operating profiles." }
        profiles.forEach { it.validate() }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Profile identifiers must be unique." }
        require(profiles.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == profiles.size) { "Profile names must be unique." }
        require(profiles.any { it.id == activeId }) { "Active profile is missing." }
        return this
    }
    fun save(profile: OperatingProfile): ProfileCollection = copy(profiles =
        if (profiles.any { it.id == profile.id }) profiles.map { if (it.id == profile.id) profile else it }
        else profiles + profile).validate()
    fun delete(id: String): ProfileCollection {
        require(id != activeId) { "Apply another profile before deleting the active profile." }
        return copy(profiles = profiles.filterNot { it.id == id }).validate()
    }
}

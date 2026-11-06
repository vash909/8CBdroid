package net.vash.ft8cbdroid.core.time

import java.net.IDN
import java.net.URI
import java.util.Locale

data class NtpPreset(val id: String, val region: String, val label: String, val host: String)

/** Operator references and regional restrictions are maintained in docs/UTC_RECEIVE_SLOTS.md. */
object NtpServers {
    val presets = listOf(
        NtpPreset("global", "Worldwide", "Cloudflare (anycast)", "time.cloudflare.com"),
        NtpPreset("europe", "Europe", "METAS, Switzerland", "ntp.metas.ch"),
        NtpPreset("north-america", "North America", "NIST, United States", "time.nist.gov"),
        NtpPreset("south-america", "South America", "NIC.br, Brazil", "a.ntp.br"),
        NtpPreset("asia", "Asia", "NICT, Japan", "ntp.nict.jp"),
        NtpPreset("africa", "Africa", "NMISA, South Africa", "time.nmisa.org"),
        NtpPreset("oceania", "Oceania", "MSL, New Zealand (NZ access only)", "pool.msltime.measurement.govt.nz"),
    )
    fun normalizeHost(input: String): String {
        val host = input.trim().removeSurrounding("[", "]")
        require(host.isNotEmpty() && host.length <= 253 && host.none { it.isWhitespace() || it in "/@?#\\%" }) {
            "Enter an IPv4 address, IPv6 address or hostname without a URL or port."
        }
        if (':' in host) {
            require(host.all { it in "0123456789abcdefABCDEF:" } && runCatching { URI("ntp://[$host]").host != null }.getOrDefault(false)) {
                "Enter a valid IPv6 address without a port or interface suffix."
            }
            return host.lowercase(Locale.ROOT)
        }
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toIntOrNull() in 0..255 }) {
                "Enter a valid IPv4 address."
            }
            return parts.joinToString(".") { it.toInt().toString() }
        }
        val ascii = try { IDN.toASCII(host.removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Enter a valid hostname.") }
        require(ascii.length in 1..253 && ascii.split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }) { "Enter a valid hostname." }
        return ascii
    }
}

data class TimeSettings(val presetId: String = "global", val customHost: String = "") {
    val host: String get() = if (presetId == "custom") NtpServers.normalizeHost(customHost)
        else requireNotNull(NtpServers.presets.firstOrNull { it.id == presetId }) { "Select an available NTP server." }.host
    fun validated(): TimeSettings {
        host
        return copy(customHost = if (presetId == "custom") NtpServers.normalizeHost(customHost) else customHost.trim())
    }
}

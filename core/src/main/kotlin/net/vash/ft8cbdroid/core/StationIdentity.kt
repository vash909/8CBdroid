package net.vash.ft8cbdroid.core

import java.util.Locale

/** Station locators currently accept Maidenhead fields/squares and subsquares. */
object MaidenheadLocator {
    private val pattern = Regex("[A-R]{2}[0-9]{2}(?:[A-X]{2})?")
    fun normalize(value: String): String = value.trim().uppercase(Locale.ROOT)
    fun isValid(value: String): Boolean = pattern.matches(normalize(value))
}

/** Only these fields are persisted in the first settings feature. */
data class StationIdentity(val callsign: String = "", val grid: String = "") {
    fun normalized() = copy(callsign = CbCallsign.normalize(callsign), grid = MaidenheadLocator.normalize(grid))
    val isValid: Boolean get() = CbCallsign.isValid(callsign) && MaidenheadLocator.isValid(grid)
}

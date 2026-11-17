package net.vash.ft8cbdroid.core.time

/** Display position in a UTC-aligned FT8 period; this never schedules or authorizes output. */
data class Ft8PeriodProgress(val elapsedMillis: Int, val odd: Boolean) {
    val fraction get() = elapsedMillis / 15_000f
    companion object {
        fun atUtcMillis(utcMillis: Long) = Ft8PeriodProgress(Math.floorMod(utcMillis, 15_000L).toInt(),
            Math.floorMod(Math.floorDiv(utcMillis, 15_000L), 2L) == 1L)
    }
}

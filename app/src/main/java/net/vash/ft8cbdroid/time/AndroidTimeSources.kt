package net.vash.ft8cbdroid.time

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.core.time.*

object AndroidTimeSource : TimeSource {
    override fun read(): ClockReading {
        val before = SystemClock.elapsedRealtimeNanos()
        val utc = System.currentTimeMillis()
        val after = SystemClock.elapsedRealtimeNanos()
        return ClockReading(before + (after - before) / 2, utc)
    }
}

fun interface GpsTimeClient { suspend fun measure(): TimeMeasurement }

/** Foreground, raw GNSS UTC only. No network provider, last-known fix, coordinates or system-clock write. */
class AndroidGpsTimeClient(context: Context, private val source: TimeSource = AndroidTimeSource) : GpsTimeClient {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    @SuppressLint("MissingPermission")
    override suspend fun measure(): TimeMeasurement = withTimeout(90_000) {
        if (Build.VERSION.SDK_INT < 29) throw TimeException("GPS time requires Android 10 or later with raw GNSS boot timestamps.")
        check(app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "Precise location permission is required for GPS time. Allow it in Android app settings."
        }
        check(LocationManager.GPS_PROVIDER in manager.allProviders) { "This phone does not provide GPS/GNSS timing. Use NTP." }
        check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Enable Location/GPS on the phone, then retry." }
        withContext(Dispatchers.Main.immediate) {
            val pending = CompletableDeferred<TimeMeasurement>()
            run {
                val handler = Handler(Looper.getMainLooper())
                val validator = GnssTimeValidator()
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    @Deprecated("Legacy callback required on Android 10")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderDisabled(provider: String) {
                        pending.completeExceptionally(TimeException("GPS was disabled. Enable it and retry."))
                    }
                }
                val callback = object : GnssMeasurementsEvent.Callback() {
                    override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                        if (!pending.isActive || Build.VERSION.SDK_INT < 29) return
                        val clock = event.clock
                        if (!clock.hasFullBiasNanos() || !clock.hasLeapSecond() || !clock.hasElapsedRealtimeNanos() ||
                            !clock.hasElapsedRealtimeUncertaintyNanos() || !clock.hasTimeUncertaintyNanos() ||
                            (clock.hasBiasNanos() && !clock.hasBiasUncertaintyNanos())) return
                        val reading = GnssTimeReading(clock.timeNanos, clock.fullBiasNanos,
                            if (clock.hasBiasNanos()) clock.biasNanos else 0.0, clock.leapSecond, clock.elapsedRealtimeNanos,
                            clock.timeUncertaintyNanos + clock.elapsedRealtimeUncertaintyNanos +
                                if (clock.hasBiasNanos()) clock.biasUncertaintyNanos else 0.0, clock.hardwareClockDiscontinuityCount)
                        runCatching { validator.accept(reading, source.read()) }.getOrNull()?.let { pending.complete(it) }
                    }
                }
                try {
                    // The executor overload avoids the Android 11 callback registration defect.
                    val registered = if (Build.VERSION.SDK_INT >= 30)
                        manager.registerGnssMeasurementsCallback(java.util.concurrent.Executor { handler.post(it) }, callback)
                    else manager.registerGnssMeasurementsCallback(callback, handler)
                    check(registered) { "This phone does not expose raw GNSS measurements." }
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                    pending.await()
                } finally {
                    runCatching { manager.unregisterGnssMeasurementsCallback(callback) }
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }
    }
}

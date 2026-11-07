package net.vash.ft8cbdroid.time

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Build
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

/** Foreground satellite-derived GPS fix times only; no coordinates or system-clock write. */
class AndroidGpsTimeClient(context: Context, private val source: TimeSource = AndroidTimeSource) : GpsTimeClient {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override suspend fun measure(): TimeMeasurement = withTimeout(90_000) {
        check(app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "Precise location permission is required for GPS time. Allow it in Android app settings."
        }
        check(LocationManager.GPS_PROVIDER in manager.allProviders) { "This phone does not provide GPS/GNSS timing. Use NTP." }
        check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Enable Location/GPS on the phone, then retry." }
        withContext(Dispatchers.Main.immediate) {
            val pending = CompletableDeferred<TimeMeasurement>()
            val validator = GpsTimeValidator()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!pending.isActive) return
                    val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
                    val value = GpsTimeReading(location.time, location.elapsedRealtimeNanos,
                        location.provider == LocationManager.GPS_PROVIDER, mock)
                    runCatching { validator.accept(value, source.read()) }.getOrNull()?.let { pending.complete(it) }
                }
                override fun onProviderEnabled(provider: String) = Unit
                @Deprecated("Legacy callback required on older Android versions")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderDisabled(provider: String) {
                    pending.completeExceptionally(TimeException("GPS was disabled. Enable it and retry."))
                }
            }
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
                pending.await()
            } finally {
                runCatching { manager.removeUpdates(listener) }
            }
        }
    }
}

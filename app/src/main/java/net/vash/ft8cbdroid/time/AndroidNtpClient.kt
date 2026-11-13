package net.vash.ft8cbdroid.time

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import net.vash.ft8cbdroid.core.time.TimeMeasurement
import net.vash.ft8cbdroid.ntp.NtpClient
import net.vash.ft8cbdroid.ntp.UdpNtpClient

/** Short measurement-only leases; no network, screen or system-clock setting is changed. */
class AndroidNtpClient(context: Context, port: Int = 123) : NtpClient {
    private val app = context.applicationContext
    private val client = PreparedNtpClient(UdpNtpClient(AndroidTimeSource, port), ::acquire)
    @Suppress("DEPRECATION") // API 34+ maps this request to its supported low-latency policy.
    private fun acquire(): AutoCloseable {
        val cpu = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "8CBdroid:NTP").apply { setReferenceCounted(false) }
        val wifi = app.getSystemService(WifiManager::class.java)?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "8CBdroid:NTP")?.apply { setReferenceCounted(false) }
        fun release() {
            try { if (wifi?.isHeld == true) wifi.release() }
            finally { if (cpu.isHeld) cpu.release() }
        }
        try {
            cpu.acquire(10_000)
            wifi?.acquire()
            return AutoCloseable { release() }
        } catch (error: Exception) { release(); throw error }
    }
    override suspend fun measure(host: String): TimeMeasurement = client.measure(host)
}

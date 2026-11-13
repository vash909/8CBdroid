package net.vash.ft8cbdroid.receive

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.R

/** User-started, non-sticky receive ownership. Never starts TX or restores a session after process death. */
class ReceiveService : Service() {
    private val runtime get() = (application as EightCbApplication).receiver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var ownsSession = false
    private var ownedGeneration = -1L
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Active reception", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Reception status and Stop for an explicitly started session"
                setSound(null, null)
            })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { runtime.stop(); return START_NOT_STICKY }
        val source = intent?.getStringExtra("source")?.let { value -> ReceiveSource.entries.find { it.name == value } }
        val generation = intent?.getLongExtra("generation", -1) ?: -1
        if (source == null || !runtime.session.accepts(generation, source)) {
            if (!ownsSession) stopSelf()
            return START_NOT_STICKY
        }
        try {
            check(notificationsAllowed()) { "Allow receive notifications in Android settings, then start RX again." }
            val notification = notification(source)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification,
                if (source == ReceiveSource.MICROPHONE) {
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                }
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
            ownsSession = true
            ownedGeneration = generation
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "8CBdroid:receive").apply { setReferenceCounted(false); acquire(600_000) }
            if (source == ReceiveSource.LAN) {
                @Suppress("DEPRECATION")
                val lock = applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "8CBdroid:LAN")
                wifi = lock.apply { setReferenceCounted(false); acquire() }
            }
            scope.launch {
                var renewAt = SystemClock.elapsedRealtime() + 300_000
                while (isActive) {
                    delay(1000)
                    if (!notificationsAllowed()) { runtime.stop("Receive notifications were disabled. Allow them and start RX again."); break }
                    if (source == ReceiveSource.MICROPHONE && !runtime.hasMicrophonePermission()) {
                        runtime.stop("Microphone permission was removed. Allow access and start RX again."); break
                    }
                    // Renew only while this explicit service is alive; no screen wake lock is used.
                    if (SystemClock.elapsedRealtime() >= renewAt || wake?.isHeld == false) {
                        wake?.acquire(600_000); renewAt = SystemClock.elapsedRealtime() + 300_000
                    }
                }
            }
            scope.launch {
                runtime.session.uiState.collectLatest { state ->
                    if (!state.active && ownsSession) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
            runtime.start(generation, source)
        } catch (error: RuntimeException) {
            runtime.stop(error.message ?: "Android could not start reception. Check permissions and retry.")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notificationsAllowed(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun notification(source: ReceiveSource): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), immutable)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiveService::class.java).setAction(STOP), immutable)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_receive)
            .setContentTitle(source.label).setContentText("Reception continues across screens. Tap Stop to end the session.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Stop RX", stop).build()).build()
    }
    override fun onDestroy() {
        scope.cancel()
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        if (ownsSession && runtime.session.uiState.value.generation == ownedGeneration && runtime.session.uiState.value.active) runtime.stop("Receive service stopped. Start RX explicitly to resume.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val START = "net.vash.ft8cbdroid.START_RECEIVE"
        const val STOP = "net.vash.ft8cbdroid.STOP_RECEIVE"
        const val CHANNEL = "receive-session"
        const val NOTIFICATION = 2901
    }
}

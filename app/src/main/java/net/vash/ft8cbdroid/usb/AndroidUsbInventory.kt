package net.vash.ft8cbdroid.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.UUID

/** Enumerates descriptors and requests access only. Never opens a port or changes control lines. */
class AndroidUsbInventory(context: Context) : UsbInventory {
    private val app = context.applicationContext
    private val manager = app.getSystemService(UsbManager::class.java)
    private val session = UsbInventorySession(app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) && manager != null)
    override val state = session.state
    private val permissionAction = "${app.packageName}.USB_PERMISSION"
    private var pending: PendingIntent? = null
    private var pendingUri: Uri? = null
    private var pendingDeviceId: Int? = null
    private var registered = false
    private var closed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == permissionAction) {
                val key = state.value.pendingKey ?: return
                if (pendingUri == null || intent.data != pendingUri) return
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
                if (device?.deviceName != key || device.deviceId != pendingDeviceId) return
                // A broadcast flag alone is not proof of access; check Android's current grant.
                val granted = try { manager?.hasPermission(device) == true } catch (_: SecurityException) { false }
                session.completeRequest(key, granted)
                cancelPending()
                refresh()
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                refresh()
            }
        }
    }

    init {
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(permissionAction).apply {
                addDataScheme("eightcbdroid")
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: RuntimeException) {
            if (registered) app.unregisterReceiver(receiver)
            registered = false
            session.failure("Could not observe USB devices. Reopen the app to retry.")
        }
    }

    override fun refresh() {
        if (closed) return
        try {
            val devices = if (state.value.hostAvailable) manager!!.deviceList.values else emptyList()
            val prober = UsbSerialProber.getDefaultProber()
            session.replaceDevices(devices.sortedBy { it.deviceName }.map { device ->
                val driver = prober.probeDevice(device)
                UsbDeviceInfo(device.deviceName, device.deviceId, device.vendorId, device.productId,
                    driver?.javaClass?.simpleName, driver?.ports?.size ?: 0,
                    if (manager!!.hasPermission(device)) UsbAccess.GRANTED else UsbAccess.NOT_REQUESTED)
            })
            if (state.value.pendingKey == null) cancelPending()
        } catch (_: RuntimeException) {
            cancelPending()
            session.failure("Could not read USB devices. Tap Find USB devices to retry.")
        }
    }

    override fun requestPermission(key: String) {
        if (closed) return
        if (!registered) {
            session.failure("USB permission callbacks are unavailable. Reopen the app to retry.")
            return
        }
        refresh()
        if (!session.beginRequest(key)) return
        try {
            val device = manager!!.deviceList[key]
            if (device == null || device.deviceId != state.value.devices.find { it.key == key }?.deviceId) {
                refresh()
                return
            }
            pendingDeviceId = device.deviceId
            pendingUri = Uri.parse("eightcbdroid://usb-permission/${UUID.randomUUID()}")
            val intent = Intent(permissionAction).setPackage(app.packageName).setData(pendingUri)
            // USB service fills in device/result extras; the mutable intent is package-scoped.
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            pending = PendingIntent.getBroadcast(app, 0, intent, flags)
            manager.requestPermission(device, pending)
        } catch (_: RuntimeException) {
            cancelPending()
            session.failure("Could not request USB access. Reconnect the device and try again.")
        }
    }

    private fun cancelPending() {
        pending?.cancel()
        pending = null
        pendingUri = null
        pendingDeviceId = null
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        if (registered) app.unregisterReceiver(receiver)
        registered = false
    }
}

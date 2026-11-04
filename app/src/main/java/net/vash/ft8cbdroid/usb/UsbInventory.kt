package net.vash.ft8cbdroid.usb

import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UsbAccess { NOT_REQUESTED, REQUESTING, GRANTED, DENIED }

data class UsbDeviceInfo(
    val key: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val driver: String? = null,
    val portCount: Int = 0,
    val access: UsbAccess = UsbAccess.NOT_REQUESTED,
) {
    val serialRecognized: Boolean get() = driver != null && portCount > 0
}

data class UsbInventoryState(
    val hostAvailable: Boolean = false,
    val scanned: Boolean = false,
    val devices: List<UsbDeviceInfo> = emptyList(),
    val pendingKey: String? = null,
    val error: String? = null,
)

interface UsbInventory : Closeable {
    val state: StateFlow<UsbInventoryState>
    fun refresh()
    fun requestPermission(key: String)
}

/** Main-thread session state; no USB operations or Android dependencies. */
internal class UsbInventorySession(hostAvailable: Boolean) {
    private val mutableState = MutableStateFlow(UsbInventoryState(hostAvailable))
    val state = mutableState.asStateFlow()

    fun replaceDevices(devices: List<UsbDeviceInfo>) {
        val previous = state.value
        val pending = previous.pendingKey?.takeIf { key ->
            devices.any { it.key == key && it.access != UsbAccess.GRANTED &&
                previous.devices.any { old -> old.key == key && old.deviceId == it.deviceId } }
        }
        val reconciled = devices.map { device ->
            val old = previous.devices.find { it.key == device.key && it.deviceId == device.deviceId }
            when {
                device.access == UsbAccess.GRANTED -> device
                device.key == pending -> device.copy(access = UsbAccess.REQUESTING)
                old?.access == UsbAccess.DENIED -> device.copy(access = UsbAccess.DENIED)
                else -> device
            }
        }
        mutableState.value = previous.copy(scanned = true, devices = reconciled, pendingKey = pending, error = null)
    }

    fun beginRequest(key: String): Boolean {
        val current = state.value
        val device = current.devices.find { it.key == key } ?: return false
        if (!current.hostAvailable || current.pendingKey != null || !device.serialRecognized || device.access == UsbAccess.GRANTED) return false
        mutableState.value = current.copy(pendingKey = key, error = null,
            devices = current.devices.map { if (it.key == key) it.copy(access = UsbAccess.REQUESTING) else it })
        return true
    }

    fun completeRequest(key: String, granted: Boolean) {
        val current = state.value
        if (current.pendingKey != key) return
        mutableState.value = current.copy(pendingKey = null, devices = current.devices.map {
            if (it.key == key) it.copy(access = if (granted) UsbAccess.GRANTED else UsbAccess.DENIED) else it
        })
    }

    fun failure(message: String) {
        val current = state.value
        mutableState.value = current.copy(pendingKey = null, error = message,
            devices = current.devices.map { if (it.access == UsbAccess.REQUESTING) it.copy(access = UsbAccess.NOT_REQUESTED) else it })
    }
}

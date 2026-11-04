package net.vash.ft8cbdroid.usb

import org.junit.Assert.*
import org.junit.Test

class UsbInventorySessionTest {
    private fun device(key: String = "/dev/bus/usb/001/002", id: Int = 2) =
        UsbDeviceInfo(key, id, 0x0403, 0x6001, "FtdiSerialDriver", 1)

    @Test fun startsUnscannedAndHandlesNoHardware() {
        val session = UsbInventorySession(true)
        assertFalse(session.state.value.scanned)
        session.replaceDevices(emptyList())
        assertTrue(session.state.value.scanned)
        assertTrue(session.state.value.devices.isEmpty())
        assertFalse(session.beginRequest("missing"))
    }

    @Test fun deniesRequestsForUnknownDriversAndUnavailableHost() {
        val device = device()
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(device.copy(driver = null, portCount = 0)))
        assertFalse(session.beginRequest(device.key))
        val noHost = UsbInventorySession(false)
        noHost.replaceDevices(listOf(device))
        assertFalse(noHost.beginRequest(device.key))
    }

    @Test fun distinguishesTwoAdaptersWithTheSameVendorAndProduct() {
        val first = device()
        val second = device("/dev/bus/usb/001/003", 3)
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(first, second))
        assertTrue(session.beginRequest(second.key))
        assertEquals(UsbAccess.NOT_REQUESTED, session.state.value.devices.first().access)
        assertEquals(UsbAccess.REQUESTING, session.state.value.devices.last().access)
        session.completeRequest(first.key, true)
        assertEquals(second.key, session.state.value.pendingKey)
        session.completeRequest(second.key, true)
        assertEquals(UsbAccess.GRANTED, session.state.value.devices.last().access)
        assertNull(session.state.value.pendingKey)
    }

    @Test fun preventsConcurrentPermissionRequestsAndAllowsDeniedRetry() {
        val device = device()
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(device))
        assertTrue(session.beginRequest(device.key))
        assertFalse(session.beginRequest(device.key))
        session.completeRequest(device.key, false)
        session.replaceDevices(listOf(device))
        assertEquals(UsbAccess.DENIED, session.state.value.devices.single().access)
        assertTrue(session.beginRequest(device.key))
        session.completeRequest(device.key, true)
        assertFalse(session.beginRequest(device.key))
    }

    @Test fun refreshKeepsPendingRequestUntilAndroidGrantsAccess() {
        val device = device()
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(device))
        session.beginRequest(device.key)
        session.replaceDevices(listOf(device))
        assertEquals(UsbAccess.REQUESTING, session.state.value.devices.single().access)
        session.replaceDevices(listOf(device.copy(access = UsbAccess.GRANTED)))
        assertNull(session.state.value.pendingKey)
        assertEquals(UsbAccess.GRANTED, session.state.value.devices.single().access)
    }

    @Test fun detachInvalidatesPendingCallbackAndReattachStartsFresh() {
        val device = device()
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(device))
        session.beginRequest(device.key)
        session.replaceDevices(emptyList())
        session.completeRequest(device.key, true)
        assertTrue(session.state.value.devices.isEmpty())
        assertNull(session.state.value.pendingKey)
        session.replaceDevices(listOf(device))
        assertEquals(UsbAccess.NOT_REQUESTED, session.state.value.devices.single().access)
    }

    @Test fun changedDeviceIdentityCannotInheritPendingOrDeniedAccess() {
        val device = device()
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(device))
        session.beginRequest(device.key)
        session.replaceDevices(listOf(device.copy(deviceId = 3)))
        assertNull(session.state.value.pendingKey)
        assertEquals(UsbAccess.NOT_REQUESTED, session.state.value.devices.single().access)
        session.beginRequest(device.key)
        session.completeRequest(device.key, false)
        session.replaceDevices(listOf(device.copy(deviceId = 4)))
        assertEquals(UsbAccess.NOT_REQUESTED, session.state.value.devices.single().access)
    }

    @Test fun revocationDoesNotKeepAnOldGrant() {
        val device = device()
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(device.copy(access = UsbAccess.GRANTED)))
        session.replaceDevices(listOf(device))
        assertEquals(UsbAccess.NOT_REQUESTED, session.state.value.devices.single().access)
        assertTrue(session.beginRequest(device.key))
    }

    @Test fun failureReleasesPendingRequestAndRefreshClearsError() {
        val device = device()
        val session = UsbInventorySession(true)
        session.replaceDevices(listOf(device))
        session.beginRequest(device.key)
        session.failure("USB unavailable")
        assertNull(session.state.value.pendingKey)
        assertEquals(UsbAccess.NOT_REQUESTED, session.state.value.devices.single().access)
        assertEquals("USB unavailable", session.state.value.error)
        session.replaceDevices(listOf(device))
        assertNull(session.state.value.error)
        assertTrue(session.beginRequest(device.key))
    }
}

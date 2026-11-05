package net.vash.ft8cbdroid.usb

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class UsbViewModelTest {
    @Test fun requiresExplicitActionsAndReleasesInventoryWithOwner() {
        val inventory = FakeInventory()
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { UsbViewModel(inventory) } }
        val model = ViewModelProvider(store, factory)[UsbViewModel::class.java]
        assertEquals(0, inventory.scans)
        assertNull(inventory.requested)
        model.refresh()
        assertEquals(1, inventory.scans)
        model.requestPermission("selected-device")
        assertEquals("selected-device", inventory.requested)
        inventory.state.value = UsbInventoryState(hostAvailable = true, scanned = true)
        assertTrue(model.uiState.value.scanned)
        assertSame(model, ViewModelProvider(store, factory)[UsbViewModel::class.java])
        assertEquals(0, inventory.closes)
        store.clear()
        assertEquals(1, inventory.closes)
    }

    private class FakeInventory : UsbInventory {
        override val state = MutableStateFlow(UsbInventoryState())
        var scans = 0
        var closes = 0
        var requested: String? = null
        override fun refresh() { scans++ }
        override fun requestPermission(key: String) { requested = key }
        override fun close() { closes++ }
    }
}

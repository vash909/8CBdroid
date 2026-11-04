package net.vash.ft8cbdroid.usb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class UsbViewModel(private val inventory: UsbInventory) : ViewModel() {
    val uiState = inventory.state
    fun refresh() = inventory.refresh()
    fun requestPermission(key: String) = inventory.requestPermission(key)
    override fun onCleared() = inventory.close()

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { UsbViewModel(AndroidUsbInventory(context)) }
        }
    }
}

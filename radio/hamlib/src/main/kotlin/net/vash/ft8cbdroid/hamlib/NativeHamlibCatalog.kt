package net.vash.ft8cbdroid.hamlib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.radio.*

/** Catalog and fixed Dummy operations only. This adapter exposes no hardware open. */
class NativeHamlibCatalog : RadioCatalog {
    override suspend fun load(): RadioCatalogSnapshot = serialized {
        RadioCatalogSnapshot(NativeHamlib.version(), NativeHamlib.models().toList())
    }
    override suspend fun checkDummy(): DummyRadioCheck = serialized {
        val values = NativeHamlib.dummy()
        check(values.size == 3) { "Invalid Hamlib Dummy response" }
        DummyRadioCheck(values[0], if (values[1] == 1L) "USB" else "Unknown", values[2] == 0L)
    }
    private suspend fun <T> serialized(action: () -> T): T = withContext(Dispatchers.IO) {
        gate.withLock {
            ensureActive()
            NativeHamlib.loadLibrary()
            action().also { ensureActive() }
        }
    }
    private companion object { val gate = Mutex() }
}

internal object NativeHamlib {
    private val loaded by lazy { System.loadLibrary("eightcbdroid_hamlib") }
    fun loadLibrary() { loaded }
    external fun version(): String
    external fun models(): Array<RadioModel>
    external fun dummy(): LongArray
}

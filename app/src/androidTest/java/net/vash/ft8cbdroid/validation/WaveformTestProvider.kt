package net.vash.ft8cbdroid.validation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/** Installed only in the test APK. Only the debuggable target app UID can access its fixture. */
class WaveformTestProvider : ContentProvider() {
    private val failures = AtomicInteger()
    private val writes = AtomicInteger()
    override fun onCreate() = true
    private fun authorize() {
        val app = requireNotNull(context).packageManager.getApplicationInfo("net.vash.ft8cbdroid", 0)
        check(app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && Binder.getCallingUid() == app.uid) {
            "Only the debug target app can access this test provider"
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        if (uri.path == "/fail") {
            failures.incrementAndGet()
            throw FileNotFoundException("Intentional R07 write failure")
        }
        if (uri.path != "/success") throw FileNotFoundException("Unknown test document")
        val file = File(requireNotNull(context).filesDir, "r07-test.wav")
        if (mode == "r") return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        writes.incrementAndGet()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        return Bundle().apply { putInt("failures", failures.get()); putInt("writes", writes.get()) }
    }
    override fun getType(uri: Uri) = "audio/wav"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

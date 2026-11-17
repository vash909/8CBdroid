package net.vash.ft8cbdroid.logbook

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.*
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.core.logbook.LogBackup

/** SAF descriptors only. Nonblocking poll keeps pipe cancellation bounded; no broad storage permission. */
class AndroidLogDocuments(private val context: Context) : LogDocuments {
    private suspend fun <T> descriptor(document: String, mode: String, block: suspend (ParcelFileDescriptor) -> T): T = try {
        withTimeout(30_000) {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val signal = CancellationSignal(); val current = AtomicReference<ParcelFileDescriptor?>()
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { signal.cancel(); runCatching { current.getAndSet(null)?.close() } }
                    }
                    try {
                        val file = context.contentResolver.openFileDescriptor(Uri.parse(document), mode, signal) ?: throw IOException("No document descriptor.")
                        current.set(file); ensureActive()
                        if (Build.VERSION.SDK_INT >= 30) {
                            Os.fcntlInt(file.fileDescriptor, OsConstants.F_SETFL, Os.fcntlInt(file.fileDescriptor, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                        } else {
                            require(OsConstants.S_ISREG(Os.fstat(file.fileDescriptor).st_mode)) {
                                "On Android 8–10, download the log file locally before opening it, or choose local storage for export."
                            }
                        }
                        block(file)
                    } finally { runCatching { current.getAndSet(null)?.close() }; cancellation.cancelAndJoin() }
                }
            }
        }
    } catch (_: TimeoutCancellationException) { throw IOException("Document operation timed out. Download the file locally or choose another destination, then retry.") }
    override suspend fun read(document: String): ByteArray = descriptor(document, "r") { file ->
        require(file.statSize <= LogBackup.MAX_BYTES) { "The log file exceeds 4 MiB." }
        val out = ByteArrayOutputStream(); val bytes = ByteArray(8192)
        val poll = StructPollfd().apply { fd = file.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (Os.poll(arrayOf(poll), 200) == 0) continue
            val count = try { Os.read(file.fileDescriptor, bytes, 0, minOf(bytes.size, LogBackup.MAX_BYTES + 1 - out.size())) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e }
            if (count == 0) break
            out.write(bytes, 0, count); require(out.size() <= LogBackup.MAX_BYTES) { "The log file exceeds 4 MiB." }
        }
        out.toByteArray()
    }
    override suspend fun write(document: String, bytes: ByteArray) { descriptor(document, "wt") { file ->
        require(bytes.size <= LogBackup.MAX_BYTES)
        var position = 0; val poll = StructPollfd().apply { fd = file.fileDescriptor; events = OsConstants.POLLOUT.toShort() }
        while (position < bytes.size) {
            currentCoroutineContext().ensureActive()
            if (Os.poll(arrayOf(poll), 200) == 0) continue
            val written = try { Os.write(file.fileDescriptor, bytes, position, bytes.size - position) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e }
            check(written > 0) { "Document writer closed early." }; position += written
        }
    } }
    override suspend fun share(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(bytes.size <= LogBackup.MAX_BYTES)
        val directory = File(context.cacheDir, "log-share").apply { mkdirs() }
        val now = System.currentTimeMillis()
        directory.listFiles().orEmpty().filter { now - it.lastModified() > 86_400_000 }.forEach { it.delete() }
        directory.listFiles().orEmpty().sortedBy { it.lastModified() }.dropLast(3).forEach { it.delete() }
        val file = File(directory, "8CBdroid-${UUID.randomUUID()}.adi")
        try { FileOutputStream(file).use { it.write(bytes); it.fd.sync() } }
        catch (e: Exception) { file.delete(); throw e }
        FileProvider.getUriForFile(context, context.packageName + ".logfiles", file).toString()
    }
}

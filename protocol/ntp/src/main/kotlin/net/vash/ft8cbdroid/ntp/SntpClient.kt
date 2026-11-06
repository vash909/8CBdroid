package net.vash.ft8cbdroid.ntp

import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.*
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.core.time.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.pow

fun interface NtpClient { suspend fun measure(host: String): TimeMeasurement }

/** RFC 5905 client/server exchange validation, independently testable without a network. */
object SntpPacket {
    private const val NTP_EPOCH = 2_208_988_800L
    fun request(reading: ClockReading, nonce: Int): ByteArray = ByteArray(48).also {
        it[0] = 0x23
        val buffer = ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(40, (Math.floorDiv(reading.unixMillis, 1000) + NTP_EPOCH).toInt())
        // Random fractional bits bind the response to this exchange; t1 is retained separately.
        buffer.putInt(44, nonce)
    }
    private fun timestamp(bytes: ByteArray, offset: Int, nearMillis: Long): Double {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val seconds = buffer.getInt(offset).toLong() and 0xffff_ffffL
        val fraction = buffer.getInt(offset + 4).toLong() and 0xffff_ffffL
        require(seconds != 0L || fraction != 0L) { "The NTP server returned an empty timestamp." }
        val nearSeconds = nearMillis / 1000 + NTP_EPOCH
        val era = kotlin.math.round((nearSeconds - seconds) / 4_294_967_296.0).toLong()
        return (seconds + era * 4_294_967_296L - NTP_EPOCH) * 1000.0 + fraction * (1000.0 / 4_294_967_296.0)
    }
    fun decode(request: ByteArray, response: ByteArray, sent: ClockReading, received: ClockReading, host: String): TimeMeasurement {
        require(request.size == 48 && response.size == 48) { "Expected a 48-byte NTP reply." }
        val flags = response[0].toInt() and 255
        require(flags ushr 6 == 0) { "The NTP server reports an unsynchronized clock or leap transition." }
        require((flags ushr 3 and 7) in 3..4 && flags and 7 == 4) { "Invalid NTP version or server mode." }
        require((response[1].toInt() and 255) in 1..15) { "NTP server is unsynchronized or requests reduced traffic." }
        require(request.copyOfRange(40, 48).contentEquals(response.copyOfRange(24, 32))) { "NTP reply does not match this request." }
        val elapsed = (received.monotonicNanos - sent.monotonicNanos) / 1e6
        require(elapsed in 0.0..3000.0 && abs((received.unixMillis - sent.unixMillis) - elapsed) <= 50) {
            "The local clock changed during the NTP request. Retry."
        }
        val t2 = timestamp(response, 32, sent.unixMillis)
        val t3 = timestamp(response, 40, sent.unixMillis)
        require(t3 >= t2 && t3 - t2 <= elapsed + 1) { "Invalid NTP receive/transmit order." }
        val delay = (elapsed - (t3 - t2)).coerceAtLeast(0.0)
        val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
        val rootDelay = buffer.getInt(4) / 65536.0 * 1000
        val dispersion = (buffer.getInt(8).toLong() and 0xffff_ffffL) / 65536.0 * 1000
        require(abs(rootDelay) <= 1000 && dispersion <= 1000) { "NTP root distance is too large." }
        val precision = 2.0.pow(response[3].toInt()) * 1000
        val uncertainty = delay / 2 + abs(rootDelay) / 2 + dispersion + precision + 2
        require(uncertainty <= 100) { "NTP uncertainty exceeds 100 ms. Choose a closer server or retry later." }
        val t4 = sent.unixMillis + elapsed
        val offset = ((t2 - sent.unixMillis) + (t3 - t4)) / 2
        require(abs(offset) <= 86_400_000) { "NTP differs by over one day. Check Android's date first." }
        return TimeMeasurement("NTP: $host", received.monotonicNanos, ((t4 + offset) * 1e6).toLong(), uncertainty, received, delay)
    }
}

/** Bounded DNS admission: an uninterruptible OS resolver cannot accumulate threads or requests. */
private object BoundedDns {
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { job -> Thread(job, "8CBdroid-DNS").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    suspend fun resolve(host: String): InetAddress = withTimeout(3000) {
        suspendCancellableCoroutine { continuation ->
            try {
                val future = worker.submit {
                    try { val value = InetAddress.getAllByName(host).first(); if (continuation.isActive) continuation.resume(value) }
                    catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
                continuation.invokeOnCancellation { future.cancel(true) }
            } catch (_: RejectedExecutionException) {
                continuation.resumeWithException(TimeException("The previous DNS lookup is still finishing. Retry later."))
            }
        }
    }
}

/** Explicit one-shot query, UDP/123, 64-second admission interval; no automatic polling/failover. */
class UdpNtpClient(private val source: TimeSource, private val port: Int = 123, private val intervalMillis: Long = 64_000) : NtpClient {
    private var lastAttempt = Long.MIN_VALUE
    private var lastNictAttempt = Long.MIN_VALUE
    @Synchronized private fun admit(now: Long, host: String) {
        check(lastAttempt == Long.MIN_VALUE || now - lastAttempt >= intervalMillis * 1_000_000) {
            "Wait 64 seconds between NTP requests."
        }
        check(host != "ntp.nict.jp" || lastNictAttempt == Long.MIN_VALUE || now - lastNictAttempt >= 256_000_000_000L) {
            "Wait 256 seconds between NICT requests."
        }
        lastAttempt = now
        if (host == "ntp.nict.jp") lastNictAttempt = now
    }
    override suspend fun measure(host: String): TimeMeasurement {
        val normalized = NtpServers.normalizeHost(host)
        admit(source.read().monotonicNanos, normalized)
        val address = BoundedDns.resolve(normalized)
        return withContext(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.connect(address, port); socket.soTimeout = 200
                val sent = source.read()
                val request = SntpPacket.request(sent, SecureRandom().nextInt())
                socket.send(DatagramPacket(request, request.size))
                val response = ByteArray(512)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (source.read().monotonicNanos - sent.monotonicNanos > 3_000_000_000L)
                        throw TimeException("The NTP server did not reply within three seconds. Check the server/network and retry later.")
                    val packet = DatagramPacket(response, response.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    return@withContext SntpPacket.decode(request, response.copyOf(packet.length), sent, source.read(), normalized)
                }
                @Suppress("UNREACHABLE_CODE") error("NTP receive ended")
            }
        }
    }
}

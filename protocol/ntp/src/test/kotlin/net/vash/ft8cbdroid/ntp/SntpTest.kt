package net.vash.ft8cbdroid.ntp

import java.net.*
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.core.time.*
import org.junit.Assert.*
import org.junit.Test

class SntpTest {
    private fun stamp(bytes: ByteArray, index: Int, millis: Long) {
        ByteBuffer.wrap(bytes).putInt(index, (millis / 1000 + 2_208_988_800L).toInt())
            .putInt(index + 4, ((millis % 1000) * 4_294_967_296L / 1000).toInt())
    }
    private fun response(request: ByteArray, receive: Long, transmit: Long) = ByteArray(48).also {
        it[0] = 0x24; it[1] = 2; it[3] = -20
        request.copyInto(it, 24, 40, 48); stamp(it, 32, receive); stamp(it, 40, transmit)
    }
    @Test fun computesOffsetDelayAndUncertaintyUsingMonotonicElapsedTime() {
        val sent = ClockReading(10_000_000_000, 1_800_000_000_000); val received = ClockReading(10_040_000_000, sent.unixMillis + 40)
        val request = SntpPacket.request(sent, 123)
        val result = SntpPacket.decode(request, response(request, sent.unixMillis + 70, sent.unixMillis + 75), sent, received, "example.org")
        assertEquals(52.5, result.offsetMillis(), .001); assertEquals(35.0, result.roundTripMillis!!, .001)
        assertEquals(19.5, result.uncertaintyMillis!!, .001)
    }
    @Test fun rejectsWrongOriginModeStratumLeapMissingTimeAndPoorQuality() {
        val sent = ClockReading(10_000_000_000, 1_800_000_000_000); val received = ClockReading(10_040_000_000, sent.unixMillis + 40)
        val request = SntpPacket.request(sent, 123)
        for (mutation in 0..7) {
            val bytes = response(request, sent.unixMillis + 20, sent.unixMillis + 21)
            when (mutation) {
                0 -> bytes[24] = (bytes[24].toInt() xor 1).toByte()
                1 -> bytes[0] = 0x23
                2 -> bytes[1] = 0
                3 -> bytes[0] = 0xe4.toByte()
                4 -> bytes.fill(0, 40, 48)
                5 -> stamp(bytes, 40, sent.unixMillis - 1)
                6 -> ByteBuffer.wrap(bytes).putInt(8, 65536)
                else -> stamp(bytes, 40, sent.unixMillis + 1000)
            }
            try { SntpPacket.decode(request, bytes, sent, received, "test"); fail("Accepted mutation $mutation") } catch (_: IllegalArgumentException) { }
        }
        try { SntpPacket.decode(request, ByteArray(47), sent, received, "test"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun unfoldsThe2036EraAndRejectsLocalClockSteps() {
        val sent = ClockReading(10_000_000_000, java.time.Instant.parse("2036-02-07T06:29:00Z").toEpochMilli())
        val request = SntpPacket.request(sent, 234); val received = ClockReading(10_020_000_000, sent.unixMillis + 20)
        val reply = response(request, sent.unixMillis + 10, sent.unixMillis + 11)
        assertEquals(.5, SntpPacket.decode(request, reply, sent, received, "test").offsetMillis(), .001)
        try { SntpPacket.decode(request, reply, sent, received.copy(unixMillis = sent.unixMillis + 2000), "test"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun actualUdpExchangeAndRateLimitUseTheSameValidatedProtocol() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2000
            val responder = async(Dispatchers.IO) {
                val request = DatagramPacket(ByteArray(512), 512); server.receive(request)
                val now = System.currentTimeMillis(); val bytes = response(request.data.copyOf(request.length), now, now)
                server.send(DatagramPacket(bytes, bytes.size, request.socketAddress))
            }
            val source = TimeSource { ClockReading(System.nanoTime(), System.currentTimeMillis()) }
            val client = UdpNtpClient(source, server.localPort)
            assertTrue(client.measure(InetAddress.getLoopbackAddress().hostAddress!!).uncertaintyMillis!! < 100)
            responder.await()
            try { client.measure("localhost"); fail("Rate limit bypassed") } catch (_: IllegalStateException) { }
        }
    }
    @Test fun slowNoncePreparationCannotBiasTheWireRoundTripOrOffset() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2000
            val elapsed = java.util.concurrent.atomic.AtomicLong(10_000_000_000)
            val base = 1_800_000_000_000L
            val source = TimeSource { val n=elapsed.get();ClockReading(n,base+(n-10_000_000_000)/1_000_000) }
            val responder = async(Dispatchers.IO) {
                val request=DatagramPacket(ByteArray(512),512);server.receive(request)
                val now=source.read().unixMillis
                val bytes=response(request.data.copyOf(request.length),now,now)
                server.send(DatagramPacket(bytes,bytes.size,request.socketAddress))
            }
            val client=UdpNtpClient(source,server.localPort,0) { elapsed.addAndGet(500_000_000);123 }
            val measured=client.measure(InetAddress.getLoopbackAddress().hostAddress!!)
            assertEquals(0.0,measured.roundTripMillis!!,.001)
            assertEquals(0.0,measured.offsetMillis(),.001)
            responder.await()
        }
    }
    @Test fun cancellingARealPendingReceiveReleasesTheSocketAndAllowsRetry() = runBlocking {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 2000
            val source = TimeSource { ClockReading(System.nanoTime(), System.currentTimeMillis()) }
            val client = UdpNtpClient(source, server.localPort, 0)
            val pending = launch { client.measure(InetAddress.getLoopbackAddress().hostAddress!!) }
            withContext(Dispatchers.IO) { server.receive(DatagramPacket(ByteArray(512), 512)) }
            withTimeout(1000) { pending.cancelAndJoin() }
            val responder = async(Dispatchers.IO) {
                val request = DatagramPacket(ByteArray(512), 512); server.receive(request)
                val now = System.currentTimeMillis(); val bytes = response(request.data.copyOf(request.length), now, now)
                server.send(DatagramPacket(bytes, bytes.size, request.socketAddress))
            }
            assertTrue(client.measure(InetAddress.getLoopbackAddress().hostAddress!!).uncertaintyMillis!! < 100)
            responder.await()
        }
    }
}

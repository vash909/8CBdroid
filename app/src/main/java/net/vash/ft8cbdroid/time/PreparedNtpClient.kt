package net.vash.ft8cbdroid.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import net.vash.ft8cbdroid.core.time.TimeMeasurement
import net.vash.ft8cbdroid.ntp.NtpClient

/** One request, including radio preparation, with joined resource release on every exit. */
internal class PreparedNtpClient(private val delegate: NtpClient, private val acquire: () -> AutoCloseable) : NtpClient {
    override suspend fun measure(host: String): TimeMeasurement = withTimeout(8_000) {
        acquire().use {
            // Let the radio leave power saving before the protocol timestamps its packet.
            delay(250)
            delegate.measure(host)
        }
    }
}

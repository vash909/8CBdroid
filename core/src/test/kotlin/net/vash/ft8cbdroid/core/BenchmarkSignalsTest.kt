package net.vash.ft8cbdroid.core

import java.security.MessageDigest
import net.vash.ft8cbdroid.reference.BenchmarkSignals
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkSignalsTest {
    private fun hash(pcm: Ft8PcmSlot) = MessageDigest.getInstance("SHA-256")
        .digest(BenchmarkSignals.pcm16(pcm)).joinToString("") { "%02x".format(it) }
    @Test fun pcmMatchesIndependentPythonBenchmarkManifest() {
        // Golden SHA-256 values from reference/wsjtcb/recordings/benchmark.json.
        assertEquals("da20be79f14f3542a9a4811ff1ab80b1eebc4bd55a5c7b95ac37d869b271d352", hash(BenchmarkSignals.signal(null, null, 903, 0)))
        assertEquals("51c1ca3958c24e0cd0b0aa94e069a9471be0022a5610fe579381ba7c4a8ae79b", hash(BenchmarkSignals.signal(null, null, 905, 300)))
    }
}

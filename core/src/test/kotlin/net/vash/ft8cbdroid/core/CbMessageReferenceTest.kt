package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.PackResult
import net.vash.ft8cbdroid.core.message.UnpackResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CbMessageReferenceTest(
    private val id: String, private val input: String, private val payload: String,
    private val accepted: Boolean, private val coldText: String,
) {
    @Test fun packsLikeDesktopOrExplicitlyRejectsRejectedPayload() {
        val actual = CbMessageCodec.pack(input)
        if (accepted) {
            assertTrue("$id: $actual", actual is PackResult.Encoded)
            assertEquals(id, payload, (actual as PackResult.Encoded).payload.toBitString())
        } else assertTrue("$id: $actual", actual is PackResult.Unsupported)
    }

    @Test fun coldUnpackMatchesDesktopWithoutInventingHashIdentities() {
        val actual = CbMessageCodec.unpack(Ft8Payload77.fromBits(payload))
        if (accepted) {
            assertTrue("$id: $actual", actual is UnpackResult.Decoded)
            assertEquals(id, coldText, (actual as UnpackResult.Decoded).message.text)
        } else assertTrue("$id: $actual", actual is UnpackResult.Unsupported)
    }

    companion object {
        private fun ascii(hex: String): String = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = requireNotNull(CbMessageReferenceTest::class.java.getResourceAsStream("/wsjtcb/messages.tsv"))
            .bufferedReader().useLines { lines -> lines.filter { !it.startsWith('#') && it.isNotEmpty() }.map {
                val fields = it.split('\t')
                arrayOf<Any>(fields[0], ascii(fields[1]), fields[2], fields[3] == "1", ascii(fields[4]))
            }.toList() }
    }
}

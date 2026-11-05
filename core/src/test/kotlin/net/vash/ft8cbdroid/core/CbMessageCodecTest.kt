package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.message.*
import org.junit.Assert.*
import org.junit.Test

class CbMessageCodecTest {
    private fun pack(text: String) = CbMessageCodec.pack(text) as PackResult.Encoded

    @Test fun lossesAreVisibleAndExplicitTxIdentitiesOnlyAffectThePreview() {
        for ((input, preview) in listOf(
            "<1AT106> 26AT101 R-10" to "<1AT106> 26AT101",
            "CQ 1AT106 JN45" to "CQ 1AT106",
            "1AT106 26AT101 -10" to "1AT106 26AT10",
            "HELLO WORLD 123456789" to "HELLO WORLD 1",
            "K1ABC W9XYZ +99" to "K1ABC W9XYZ -02",
            "CQ K1ABC FN42AB" to "CQ K1ABC FN42",
        )) {
            val encoded = pack(input)
            assertEquals(preview, encoded.transmittedText)
            assertTrue(input, encoded.contentChanged)
        }
        val normal = pack(" cq   1at106 ")
        assertEquals("CQ 1AT106", normal.transmittedText)
        assertFalse(normal.contentChanged)
    }

    @Test fun packingDoesNotTeachTheReceiverOrEraseTheHashValue() {
        val encoded = pack("<1AT106> 26AT101")
        repeat(2) {
            val decoded = (CbMessageCodec.unpack(encoded.payload) as UnpackResult.Decoded).message
            assertEquals("<...> 26AT101", decoded.text)
            val hash = (decoded.parts[0] as MessagePart.UnresolvedCall).hash
            assertEquals(CbCallsignHash.calculate("1AT106", 12), hash)
            pack("CQ 1AT106")
        }
        val standard = pack("K1ABC <1AT106> -10")
        assertEquals(22, (standard.message.parts[1] as MessagePart.UnresolvedCall).hash.width)
    }

    @Test fun unsupportedInputsNeverProducePayloads() {
        for (input in listOf("", "<...> 26AT101", "<A> 26AT101", "CQ 53HDFKJEASD",
            "CQ\t1AT106", "CQ １AT106", "X".repeat(38), "ABC123", "TU; K1ABC W9XYZ", "K1ABC W9XYZ 16A EMA", "$" + "DX -10")) {
            assertTrue(input, CbMessageCodec.pack(input) is PackResult.Unsupported)
        }
    }

    @Test fun explicitFreeTextDoesNotSelectTelemetry() {
        val result = CbMessageCodec.pack("ABC123", EncodingMode.FREE_TEXT) as PackResult.Encoded
        assertEquals(MessageFormat.FREE_TEXT, result.message.format)
        assertEquals("ABC123", result.transmittedText)
    }

    @Test fun unsupportedAndEmptyPayloadsStayUnsupported() {
        for (bits in listOf("0".repeat(77), "0".repeat(74) + "110", "0".repeat(71) + "101000"))
            assertTrue(CbMessageCodec.unpack(Ft8Payload77.fromBits(bits)) is UnpackResult.Unsupported)
    }

    @Test fun arbitraryPayloadsCannotEscapeTheTypedFailureContract() {
        val random = java.util.Random(8)
        repeat(2_000) {
            val bytes = ByteArray(10).also(random::nextBytes)
            bytes[9] = (bytes[9].toInt() and 0xf8).toByte()
            CbMessageCodec.unpack(Ft8Payload77.fromBytes(bytes))
        }
    }

    @Test fun rawReceiveFramesPreserveDesktopAcceptance() {
        val rows = requireNotNull(javaClass.getResourceAsStream("/wsjtcb/messages-rx.tsv"))
            .bufferedReader().readLines().filter { !it.startsWith('#') && it.isNotEmpty() }
        for (row in rows) {
            val fields = row.split('\t')
            val result = CbMessageCodec.unpack(Ft8Payload77.fromBits(fields[1]))
            if (fields[2] == "1") {
                assertTrue(fields[0], result is UnpackResult.Decoded)
                val expected = fields[3].chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                assertEquals(fields[0], expected, (result as UnpackResult.Decoded).message.text)
            } else assertTrue(fields[0], result is UnpackResult.Unsupported)
        }
    }

    @Test fun hashesUseTheDesktopWraparoundAndCollisionValues() {
        assertEquals(482, CbCallsignHash.calculate("1AT0002", 10).value)
        assertEquals(1928, CbCallsignHash.calculate("1AT0002", 12).value)
        assertEquals(CbCallsignHash.calculate("1AT0002", 12), CbCallsignHash.calculate("1AT0050", 12))
        assertNotEquals(CbCallsignHash.calculate("1AT0002", 22), CbCallsignHash.calculate("1AT0050", 22))
        assertEquals(2524178, CbCallsignHash.calculate("5AT179", 22).value)
    }
}

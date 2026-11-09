package net.vash.ft8cbdroid.core

import java.time.Instant
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.recording.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ManualTemplateReferenceTest(private val id: String, private val own: String, private val peer: String,
    private val report: Int, private val kind: ManualMessage, private val expected: String, private val payload: String) {
    @Test fun generatedTextAndWirePayloadMatchThePinnedDesktop() {
        assertEquals(expected, standardManualTemplates(own, "JN45", peer, report)[kind])
        val packed = CbMessageCodec.pack(expected)
        assertTrue("$id $kind $packed", packed is PackResult.Encoded)
        assertEquals(payload, (packed as PackResult.Encoded).payload.toBitString())
        if (CbCallsign.isValid(own) && (peer.isEmpty() || CbCallsign.isValid(peer)))
            assertEquals(expected, manualMessages(StationIdentity(own, "JN45AB"), peer, report)[kind])
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}/{4}") fun cases(): List<Array<Any>> =
            requireNotNull(ManualTemplateReferenceTest::class.java.getResourceAsStream("/wsjtcb/manual-messages.tsv"))
                .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                    val p = line.split('\t')
                    arrayOf(p[0], p[1], p[2], p[3].toInt(), ManualMessage.valueOf(p[4]),
                        p[5].chunked(2).map { it.toInt(16).toChar() }.joinToString(""), p[6])
                }
    }
}

class ManualPeerTest {
    private fun row(parts: List<MessagePart>, format: MessageFormat = MessageFormat.NONSTANDARD_CALL,
        flags: Set<RecordedDecodeFlag> = setOf(RecordedDecodeFlag.CRC_LDPC_VALID), second: Long = 0): LiveDecodeRow {
        val frame = Ft8DecodedFrame(Ft8Payload77.fromBits("0".repeat(77)), .5f, 1234.4f, 30, -12.4f)
        val utc = Instant.ofEpochSecond(1_800_000_000L + second)
        return LiveDecodeRow(RecordedDecode(frame, UnpackResult.Decoded(DecodedMessage(format, parts)), utc, utc, 0f, flags), "NTP/test", 1.0)
    }
    @Test fun selectionUsesTheSenderAndTheReceivedUtcPeriodWithoutArmingAnything() {
        val cq = row(listOf(MessagePart.Text("CQ"), MessagePart.Text("26AT101")))
        assertEquals(ManualPeer("26AT101", 1234, -12, SlotPeriod.ODD), manualPeer(cq, "1AT106"))
        val directed = row(listOf(MessagePart.Text("1AT106"), MessagePart.Text("26AT101")), second = 15)
        assertEquals(SlotPeriod.EVEN, manualPeer(directed, "1AT106")!!.period)
    }
    @Test fun unknownAmbiguousFreeTextSelfAndNonCbIdentitiesNeverBecomeSelectedPeers() {
        val h = CallsignHash(12, 42)
        val cases = listOf(
            row(listOf(MessagePart.Text("CQ"), MessagePart.Text("1AT106"))),
            row(listOf(MessagePart.Text("CQ"), MessagePart.Text("K1ABC"))),
            row(listOf(MessagePart.Text("26AT101 -07")), MessageFormat.FREE_TEXT),
            row(listOf(MessagePart.Text("1AT106"), MessagePart.UnresolvedCall(h))),
            row(listOf(MessagePart.Text("1AT106"), MessagePart.AmbiguousCall(h, setOf("26AT101", "161XZ085")))),
            row(listOf(MessagePart.Text("CQ"), MessagePart.Text("26AT101")), flags = emptySet()),
            row(listOf(MessagePart.Text("CQ"), MessagePart.Text("26AT101")), flags = setOf(RecordedDecodeFlag.CRC_LDPC_VALID, RecordedDecodeFlag.AMBIGUOUS_HASH)))
        cases.forEach { assertNull(manualPeer(it, "1AT106")) }
    }
    @Test fun cbValidationIsIndependentOfTheSharedDesktopRenderer() {
        for (peer in listOf("K1ABC", "<...>", "1AT106", "BAD"))
            assertTrue(runCatching { manualMessages(StationIdentity("1AT106", "JN45"), peer, -7) }.isFailure)
        assertTrue(runCatching { manualMessages(StationIdentity("K1ABC", "JN45"), "26AT101", -7) }.isFailure)
        assertTrue(runCatching { manualMessages(StationIdentity("1AT106", "JN45"), "26AT101", 100) }.isFailure)
    }
    @Test fun testToneIsBoundedWithSilenceFadesAndExplicitDigitalLevel() {
        val pcm = phoneTestTone(1000f, .4f)
        assertEquals(26400, pcm.size)
        assertTrue(pcm.take(1200).all { it == 0f } && pcm.takeLast(1200).all { it == 0f })
        assertTrue(pcm.maxOf { kotlin.math.abs(it) } <= .4f)
        assertTrue(pcm.sliceArray(1440 until 24960).count { it > .3f } > 3000)
        for (hz in listOf(Float.NaN, 199f, 2901f)) assertTrue(runCatching { phoneTestTone(hz, .4f) }.isFailure)
    }
}

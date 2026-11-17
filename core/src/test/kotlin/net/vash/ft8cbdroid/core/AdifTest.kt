package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.logbook.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AdifTest {
    private val detail = QsoDetails("1AT/AA", "26AT101", Instant.parse("2026-11-17T23:59:30Z").toEpochMilli(), Instant.parse("2026-11-18T00:00:15Z").toEpochMilli(),
        "JN44XX", "JN55", 27_265_123, -7, 12, "Portable <eOr> café 😎")
    private fun record(id: String = "record") = QsoRecord(id, null, 1, QsoOrigin.MANUAL, detail)
    private fun bytes(vararg pairs: Pair<String, String>): ByteArray = (pairs.joinToString(" ") { (k,v) -> "<$k:${v.length}>$v" }+"<eor>").toByteArray()
    private fun valid() = listOf("CALL" to "26AT101", "STATION_CALLSIGN" to "1AT106", "QSO_DATE" to "20261117", "TIME_ON" to "2359", "TIME_OFF" to "0001", "MODE" to "FT8", "FREQ" to "27.265000", "BAND" to "11m")
    @Test fun exactCbFrequencyMidnightReportsAndUnicodeNotesRoundTripWithoutInventedAudioOrigin() {
        val encoded = Adif.encode(listOf(record()), AdifBandPolicy.WSJT_CB_11M)
        assertArrayEquals(requireNotNull(javaClass.getResourceAsStream("/wsjtcb/adif/app-export.adi")).use { it.readBytes() }, encoded)
        assertTrue(encoded.all { it.toInt() in 0..127 })
        assertFalse(encoded.toString(Charsets.US_ASCII).contains("Portable <eOr>"))
        val parsed = Adif.decode(encoded)
        assertTrue(parsed.issues.isEmpty()); assertEquals(detail, parsed.records.single().details)
        assertEquals(QsoOrigin.ADIF_IMPORT, parsed.records.single().origin); assertNull(parsed.records.single().txAudioHz)
        assertNull(parsed.records.single().completionId); assertEquals(QsoTimeBasis.IMPORTED_UTC, parsed.records.single().timeBasis)
    }
    @Test fun legacyTimeTypeIndicatorsCaseLeadingZerosAndMissingEndDate() {
        val text=bytes(*valid().map { it.first.lowercase() to it.second }.toTypedArray()).toString(Charsets.US_ASCII).replace("<call:7>","<call:007:S>")
        val result=Adif.decode(text.toByteArray()); assertTrue(result.issues.isEmpty())
        assertEquals(Instant.parse("2026-11-18T00:01:00Z").toEpochMilli(), result.records.single().details.endedUtcMillis)
        assertTrue(result.warnings.any { "End date" in it.message })
    }
    @Test fun rejectUnsupportedAndIncompleteRecordsButPreviewOtherValidRecords() {
        val data=bytes(*valid().map { if(it.first=="MODE") it.first to "FT4" else it }.toTypedArray()) + bytes(*(valid().filter { it.first!="FREQ" }).toTypedArray()) + bytes(*valid().toTypedArray())
        val result=Adif.decode(data);assertEquals(2,result.issues.size);assertEquals(1,result.records.size)
    }
    @Test fun neverGuessStationOrFrequencyAndNeverTreatAnUnknownHashAsACall() {
        assertTrue(Adif.decode(bytes(*valid().filter { it.first!="STATION_CALLSIGN" }.toTypedArray())).records.isEmpty())
        val fallback=Adif.decode(bytes(*valid().filter { it.first!="STATION_CALLSIGN" }.toTypedArray()), "161AT101")
        assertEquals("161AT101",fallback.records.single().details.ownCall);assertTrue(fallback.warnings.any { "current station" in it.message })
        assertTrue(Adif.decode(bytes(*valid().map { if(it.first=="CALL") it.first to "<...>" else it }.toTypedArray())).records.isEmpty())
    }
    @Test fun malformedLengthsTruncationAndFieldBombsCannotPartiallyImport() {
        for (text in listOf("<CALL:999>26AT101<eor>", "<CALL:-1>Q", "<CALL:2>26", "x<adif_ver:5>3.1.8", "<CALL:8:I>"+"x".repeat(8), "<X:0>".repeat(129))) {
            assertTrue(text,runCatching { Adif.decode(text.toByteArray()) }.isFailure)
        }
        assertTrue(runCatching { Adif.decode(ByteArray(Adif.MAX_BYTES+1)) }.isFailure)
        assertTrue(runCatching { Adif.decode("<COMMENT:1>é<eor>".toByteArray()) }.isFailure)
    }
    @Test fun duplicateFieldsAndWrongDateFrequencyAndReportsAreVisibleRowErrors() {
        val variants=listOf(valid()+listOf("CALL" to "14AT101"),valid().map{if(it.first=="QSO_DATE") it.first to "20260230" else it},
            valid().map{if(it.first=="FREQ") it.first to "27.2650001" else it},valid()+listOf("RST_SENT" to "599"))
        for(v in variants){val parsed=Adif.decode(bytes(*v.toTypedArray()));assertEquals(1,parsed.issues.size);assertTrue(parsed.records.isEmpty())}
    }
    @Test fun strictBandConsumersGetExactFrequencyWithoutMislabelingTenMetres() {
        val strict=Adif.encode(listOf(record()),AdifBandPolicy.FREQUENCY_ONLY).toString(Charsets.US_ASCII)
        assertFalse(strict.contains("<BAND:"));assertTrue(strict.contains("27.265123"));assertFalse(strict.contains("10m"))
        for ((hz, included) in listOf(26_964_999L to false, 26_965_000L to true, 27_405_000L to true, 27_405_001L to false)) {
            val text = Adif.encode(listOf(record().copy(details = detail.copy(dialHz = hz))), AdifBandPolicy.WSJT_CB_11M).toString(Charsets.US_ASCII)
            assertEquals(included, text.contains("<BAND:"))
        }
        val outside=record().copy(details=detail.copy(dialHz=27_555_000))
        assertFalse(Adif.encode(listOf(outside),AdifBandPolicy.WSJT_CB_11M).toString(Charsets.US_ASCII).contains("<BAND:"))
    }
    @Test fun pinnedDesktopFixturePreservesCbCallsExactFrequencyAndMidnightUtc() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/wsjtcb/adif/desktop.adi")).use { it.readBytes() }
        val parsed = Adif.decode(bytes)
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals(2, parsed.records.size)
        val d = parsed.records.first().details
        assertEquals("26AT101", d.peerCall); assertEquals("1AT106", d.ownCall)
        assertEquals(27_265_125L, d.dialHz); assertEquals(-7, d.sentReport); assertEquals(3, d.receivedReport)
        assertEquals(Instant.parse("2026-11-17T00:00:30Z").toEpochMilli(), d.endedUtcMillis)
        assertEquals("Portable CB", d.notes)
        val again = Adif.decode(Adif.encode(parsed.records, AdifBandPolicy.WSJT_CB_11M))
        assertEquals(parsed.records.map { it.details }, again.records.map { it.details })
    }
    @Test fun standardHeaderMayBeginWithProgramIdAndIsNeverImportedAsAContact() {
        val data = "<PROGRAMID:6>Logger<ADIF_VER:5>3.1.8<EOH>".toByteArray() + bytes(*valid().toTypedArray())
        assertEquals(1, Adif.decode(data).records.size)
    }
    @Test fun fieldValuesCannotBecomeRecordTagsAndExtraMetadataCannotForgeLocalEvidence() {
        val result=Adif.decode(bytes(*(valid()+listOf("COMMENT" to "text <EOR> and <CALL:4>FAKE", "APP_X_ORIGIN" to "LOCAL_AUDIO")).toTypedArray()))
        assertEquals(1,result.records.size);assertTrue(result.records.single().details.notes.contains("<EOR>"));assertEquals(QsoOrigin.ADIF_IMPORT,result.records.single().origin)
        assertTrue(result.warnings.any { "APP_X_ORIGIN" in it.message })
    }
}

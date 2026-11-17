package net.vash.ft8cbdroid.core.logbook

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID
import net.vash.ft8cbdroid.core.CbCallsign

enum class AdifBandPolicy { WSJT_CB_11M, FREQUENCY_ONLY }
data class AdifIssue(val record: Int, val message: String)
data class AdifImport(val records: List<QsoRecord>, val issues: List<AdifIssue>, val warnings: List<AdifIssue>)

/** Bounded ASCII ADI scanner. Lengths, not tag-looking values, delimit fields. No XML/network access. */
object Adif {
    const val MAX_BYTES = LogBackup.MAX_BYTES
    private val date = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val time = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val known = setOf("CALL", "STATION_CALLSIGN", "QSO_DATE", "TIME_ON", "QSO_DATE_OFF", "TIME_OFF", "FREQ", "BAND", "MODE",
        "GRIDSQUARE", "MY_GRIDSQUARE", "RST_SENT", "RST_RCVD", "COMMENT", "NOTES", "APP_8CBDROID_NOTES_UTF8_HEX")
    fun encode(records: List<QsoRecord>, policy: AdifBandPolicy): ByteArray {
        require(records.size <= LogBackup.MAX_RECORDS) { "Export supports at most 5,000 contacts." }
        val out = StringBuilder("8CBdroid ADIF export\r\n<ADIF_VER:5>3.1.8<PROGRAMID:8>8CBdroid<EOH>\r\n")
        fun field(name: String, value: String) { out.append('<').append(name).append(':').append(value.length).append('>').append(value).append(' ') }
        for (r in records) {
            r.validate(); val d = r.details
            val start = Instant.ofEpochMilli(d.startedUtcMillis).atOffset(ZoneOffset.UTC)
            val end = Instant.ofEpochMilli(d.endedUtcMillis).atOffset(ZoneOffset.UTC)
            field("CALL", d.peerCall); field("STATION_CALLSIGN", d.ownCall); field("MODE", "FT8")
            field("QSO_DATE", start.format(date)); field("TIME_ON", start.format(time))
            field("QSO_DATE_OFF", end.format(date)); field("TIME_OFF", end.format(time))
            field("FREQ", BigDecimal.valueOf(d.dialHz, 6).stripTrailingZeros().toPlainString())
            if (policy == AdifBandPolicy.WSJT_CB_11M && d.dialHz in 26_965_000..27_405_000) field("BAND", "11m")
            if (d.ownGrid.isNotEmpty()) field("MY_GRIDSQUARE", d.ownGrid)
            if (d.peerGrid.isNotEmpty()) field("GRIDSQUARE", d.peerGrid)
            d.sentReport?.let { field("RST_SENT", it.toString()) }; d.receivedReport?.let { field("RST_RCVD", it.toString()) }
            if (d.notes.isNotEmpty()) {
                // Escape angle brackets too: the pinned desktop loader searches for EOR before parsing lengths.
                field("COMMENT", buildString { for (c in d.notes) when {
                    c == '\\' -> append("\\\\")
                    c.code in 32..126 && c != '<' && c != '>' -> append(c)
                    else -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } })
                val utf8 = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(d.notes))
                val bytes = ByteArray(utf8.remaining()); utf8.get(bytes)
                field("APP_8CBDROID_NOTES_UTF8_HEX", bytes.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 255) })
            }
            out.append("<EOR>\r\n")
            require(out.length <= MAX_BYTES) { "The ADIF export exceeds 4 MiB. Use a smaller log." }
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }
    fun decode(bytes: ByteArray, defaultOwnCall: String? = null, newId: () -> String = { UUID.randomUUID().toString() }): AdifImport {
        require(bytes.size <= MAX_BYTES) { "The ADIF file exceeds 4 MiB." }
        require(bytes.all { (it.toInt() and 255) in 32..126 || it == 13.toByte() || it == 10.toByte() || it == 9.toByte() }) {
            "ADI import requires ASCII text. Unicode notes are supported in 8CBdroid backups and app-specific ADIF fields."
        }
        val text = bytes.toString(Charsets.US_ASCII); val records = mutableListOf<QsoRecord>(); val issues = mutableListOf<AdifIssue>(); val warnings = mutableListOf<AdifIssue>()
        var pos = 0; var number = 0; var fields = linkedMapOf<String, String>(); var header = text.trimStart().firstOrNull()?.let { it != '<' } == true || text.trimStart().startsWith("<ADIF_VER:", true)
        var headerEnded = !header; var seenHeaderEnd = false; var recordError: String? = null; var fieldCount = 0
        while (pos < text.length) {
            val next = text.indexOf('<', pos)
            if (next < 0) { require(text.substring(pos).isBlank()) { "Unexpected trailing ADIF text." }; break }
            if (!header) require(text.substring(pos, next).isBlank()) { "Unexpected text between ADIF fields." }
            val close = text.indexOf('>', next + 1); require(close in (next + 1)..(next + 120)) { "Malformed ADIF tag at offset $next." }
            val tag = text.substring(next + 1, close).uppercase(Locale.ROOT); pos = close + 1
            if (tag == "EOH") { require(number == 0 && !seenHeaderEnd && recordError == null) { "Unexpected ADIF header terminator." }; header = false; headerEnded = true; seenHeaderEnd = true; fields.clear(); fieldCount = 0; continue }
            if (tag == "EOR") {
                require(!header) { "ADIF header has no EOH terminator." }
                number++; require(number <= LogBackup.MAX_RECORDS) { "Import supports at most 5,000 records." }
                try {
                    require(recordError == null) { requireNotNull(recordError) }
                    fun required(n: String) = fields[n]?.trim()?.takeIf { it.isNotEmpty() } ?: error("Missing $n.")
                    require(required("MODE").equals("FT8", true)) { "Only FT8 contacts can be imported." }
                    fun utc(d: String, t: String): LocalDateTime {
                        require(d.matches(Regex("[0-9]{8}")) && t.matches(Regex("[0-9]{4}([0-9]{2})?"))) { "Invalid UTC date/time." }
                        return LocalDateTime.of(LocalDate.parse(d, date), LocalTime.parse(if (t.length == 4) t + "00" else t, time))
                    }
                    val start = utc(required("QSO_DATE"), required("TIME_ON"))
                    val end = when {
                        fields["TIME_OFF"].isNullOrBlank() && fields["QSO_DATE_OFF"].isNullOrBlank() -> {
                            warnings.add(AdifIssue(number, "No end time; start UTC was reused.")); start
                        }
                        fields["TIME_OFF"].isNullOrBlank() -> error("QSO_DATE_OFF requires TIME_OFF.")
                        fields["QSO_DATE_OFF"].isNullOrBlank() -> {
                            warnings.add(AdifIssue(number, "End date inferred from start UTC."))
                            utc(required("QSO_DATE"), required("TIME_OFF")).let { if (it < start) it.plusDays(1) else it }
                        }
                        else -> utc(required("QSO_DATE_OFF"), required("TIME_OFF"))
                    }
                    val own = fields["STATION_CALLSIGN"]?.trim()?.takeIf { it.isNotEmpty() } ?: defaultOwnCall?.takeIf(CbCallsign::isValid)?.also {
                        warnings.add(AdifIssue(number, "Station callsign supplied from current station settings."))
                    } ?: error("Missing STATION_CALLSIGN. Set your station and retry, or use a file containing it.")
                    val freq = required("FREQ"); require(freq.matches(Regex("[0-9]+(\\.[0-9]+)?"))) { "FREQ must be a positive MHz number." }
                    val hz = BigDecimal(freq).movePointRight(6).longValueExact()
                    fun report(n: String) = fields[n]?.trim()?.takeIf { it.isNotEmpty() }?.let { requireNotNull(it.toIntOrNull()) { "Invalid $n report." } }
                    val notes = fields["APP_8CBDROID_NOTES_UTF8_HEX"]?.let { hex ->
                        require(hex.length <= 4000 && hex.length % 2 == 0 && hex.all { it in "0123456789ABCDEFabcdef" }) { "Invalid app-specific note encoding." }
                        val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
                    } ?: listOfNotNull(fields["COMMENT"], fields["NOTES"]).distinct().joinToString("\n")
                    val detail = QsoDetails(own, required("CALL"), start.toInstant(ZoneOffset.UTC).toEpochMilli(), end.toInstant(ZoneOffset.UTC).toEpochMilli(),
                        fields["MY_GRIDSQUARE"].orEmpty(), fields["GRIDSQUARE"].orEmpty(), hz, report("RST_SENT"), report("RST_RCVD"), notes).normalized().also { it.validate() }
                    records.add(QsoRecord(newId(), null, 1, QsoOrigin.ADIF_IMPORT, detail, timeBasis = QsoTimeBasis.IMPORTED_UTC).also { it.validate() })
                    val ignored = fields.keys - known
                    if (ignored.isNotEmpty()) warnings.add(AdifIssue(number, "Additional fields are not stored: ${ignored.sorted().joinToString(", ")}."))
                    if (!fields["BAND"].isNullOrBlank() && fields["BAND"]?.equals("11m", true) != true) warnings.add(AdifIssue(number, "Band label ignored; exact numeric FREQ is retained."))
                } catch (error: Exception) {
                    issues.add(AdifIssue(number, error.message?.take(180) ?: "Invalid contact."))
                }
                fields = linkedMapOf(); recordError = null; fieldCount = 0; continue
            }
            fieldCount++; require(fieldCount <= 128) { "Too many fields in one ADIF header/record." }
            val parts = tag.split(':')
            require(parts.size in 2..3 && parts[0].matches(Regex("[A-Z0-9_]{1,80}")) && parts[1].matches(Regex("[0-9]{1,8}")) &&
                (parts.size == 2 || parts[2].matches(Regex("[A-Z]")) && parts[2] !in setOf("I", "G"))) { "Malformed ADIF data specifier at offset $next." }
            val length = parts[1].toInt(); require(length <= 8192 && length <= text.length - pos) { "Invalid or truncated ADIF field length." }
            val value = text.substring(pos, pos + length); pos += length
            if (!header && fields.put(parts[0], value) != null) recordError = "Repeated ${parts[0]} field."
        }
        require(headerEnded && fields.isEmpty()) { "ADIF ends before EOH/EOR; no records were imported." }
        return AdifImport(records, issues, warnings)
    }
}

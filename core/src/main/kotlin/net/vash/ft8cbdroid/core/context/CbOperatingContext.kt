package net.vash.ft8cbdroid.core.context

import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.message.*

/** A CB division is a pinned country/territory label, not a station location. */
data class CbDivision(val prefix: String, val desktopName: String?) {
    val countryName: String? get() = when (desktopName) {
        "Sardegna" -> "Sardinia"
        "Palestina" -> "Palestine"
        else -> desktopName
    }
}

object CbCountries {
    private val base = Regex("[0-9]{1,3}[A-Z]{1,2}")
    private val countries: Map<String, String> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/wsjtcb/countries.tsv"))
        val rows = stream.bufferedReader().use { it.readLines() }.filter { it.isNotEmpty() && !it.startsWith('#') }
        val pairs = rows.map { line -> line.split('\t').also { require(it.size == 2) }.let { it[0] to it[1] } }
        require(pairs.size == 350 && pairs.map { it.first }.distinct().size == pairs.size)
        pairs.toMap()
    }

    /** Matches Radio::cb_country_prefix, including base calls, without changing station validation. */
    fun lookup(callsign: String): CbDivision? {
        val call = CbCallsign.normalize(callsign)
        if (!CbCallsign.isValid(call) && !base.matches(call)) return null
        val prefix = call.takeWhile { it in '0'..'9' }.padStart(3, '0')
        return CbDivision(prefix, countries[prefix])
    }
}

enum class ActivityKind { CQ, DIRECTED, OTHER }
enum class ActivityFilter { ALL, CQ, TO_ME }

data class CbActivityContext(
    val kind: ActivityKind = ActivityKind.OTHER,
    val sender: String? = null,
    val recipient: String? = null,
    val division: CbDivision? = null,
) {
    fun matches(filter: ActivityFilter, ownCall: String): Boolean = when (filter) {
        ActivityFilter.ALL -> true
        ActivityFilter.CQ -> kind == ActivityKind.CQ
        ActivityFilter.TO_ME -> kind == ActivityKind.DIRECTED && CbCallsign.isValid(ownCall) && recipient == CbCallsign.normalize(ownCall)
    }
}

/** Read-only metadata. It never selects peers, learns hashes or supplies QSO/AutoSeq facts. */
object CbOperatingContext {
    private val report = Regex("R?[+-][0-9]{2}")
    private fun call(part: MessagePart?): String? = when (part) {
        is MessagePart.Text -> part.value
        is MessagePart.ResolvedCall -> part.callsign
        else -> null
    }?.takeIf(CbCallsign::isValid)?.let(CbCallsign::normalize)
    private fun context(kind: ActivityKind, sender: String?, recipient: String? = null) =
        CbActivityContext(kind, sender, recipient, sender?.let(CbCountries::lookup))

    fun fromMessage(message: DecodedMessage): CbActivityContext {
        if (message.format == MessageFormat.FREE_TEXT) {
            // WSJT-CB long-call reports/finals use these exact free-text shapes.
            val words = message.text.split(' ')
            val first = words.getOrNull(0)?.takeIf(CbCallsign::isValid)?.let(CbCallsign::normalize)
            if (words.size == 2 && first != null && (report.matches(words[1]) || words[1] in setOf("RRR", "RR73", "73")))
                return context(ActivityKind.DIRECTED, null, first)
            return CbActivityContext()
        }
        val parts = message.parts
        val token = (parts.firstOrNull() as? MessagePart.Text)?.value
        if (token == "CQ" || token?.startsWith("CQ ") == true)
            return context(ActivityKind.CQ, call(parts.getOrNull(1)))
        val first = call(parts.getOrNull(0)); val second = call(parts.getOrNull(1))
        // In type 4's trailing-hash form, the full first call is the sender.
        if (message.format == MessageFormat.NONSTANDARD_CALL && parts.firstOrNull() is MessagePart.Text &&
            first != null && parts.getOrNull(1) is MessagePart.HashedCall)
            return context(ActivityKind.DIRECTED, first, second)
        if (first != null || parts.firstOrNull() is MessagePart.HashedCall)
            return context(ActivityKind.DIRECTED, second, first)
        return CbActivityContext()
    }

    /** Desktop text has no typed identity metadata. Its explicitly rendered hashes remain display-only. */
    fun fromDesktopText(text: String): CbActivityContext {
        val packed = CbMessageCodec.pack(text) as? PackResult.Encoded ?: return CbActivityContext()
        if (packed.contentChanged) return CbActivityContext()
        val supplied = packed.normalizedInput.split(' ').filter { it.startsWith('<') && it.endsWith('>') }
            .map { it.drop(1).dropLast(1) }.filter(CbCallsign::isValid)
        val parts = packed.message.parts.map { part ->
            if (part is MessagePart.UnresolvedCall) {
                val candidates = supplied.filter { CbCallsignHash.calculate(it, part.hash.width) == part.hash }.distinct()
                candidates.singleOrNull()?.let { MessagePart.ResolvedCall(part.hash, it) } ?: part
            } else part
        }
        return fromMessage(packed.message.copy(parts = parts))
    }
}

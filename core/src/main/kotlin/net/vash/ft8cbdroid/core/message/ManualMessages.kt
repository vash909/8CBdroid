package net.vash.ft8cbdroid.core.message

import java.util.Locale
import kotlin.math.roundToInt
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.audio.LiveDecodeRow
import net.vash.ft8cbdroid.core.audio.SlotPeriod
import net.vash.ft8cbdroid.core.recording.RecordedDecodeFlag

enum class ManualMessage(val label: String) { CQ("CQ"), REPLY("Reply"), REPORT("Report"), ROGER("R-report"), RR73("RR73"), SIGNOFF("73"), CUSTOM("Custom") }
data class ManualPeer(val callsign: String, val frequencyHz: Int, val report: Int?, val period: SlotPeriod)

/** Explicit CB peer selection from a current integrity-checked local decode, never from free text or an unknown hash. */
fun manualPeer(row: LiveDecodeRow, ownCall: String): ManualPeer? {
    if (RecordedDecodeFlag.CRC_LDPC_VALID !in row.decode.flags ||
        row.decode.flags.any { it in setOf(RecordedDecodeFlag.UNKNOWN_HASH, RecordedDecodeFlag.AMBIGUOUS_HASH) }) return null
    val message = (row.decode.message as? UnpackResult.Decoded)?.message ?: return null
    if (message.format == MessageFormat.FREE_TEXT || message.parts.size < 2) return null
    val sender = when (val part = message.parts[1]) {
        is MessagePart.Text -> part.value
        is MessagePart.ResolvedCall -> part.callsign
        else -> return null
    }
    if (!CbCallsign.isValid(sender) || CbCallsign.normalize(sender) == CbCallsign.normalize(ownCall)) return null
    val index = Math.floorDiv(row.decode.slotStartUtc.epochSecond, 15)
    return ManualPeer(CbCallsign.normalize(sender), row.decode.frame.audioFrequencyHz.roundToInt(),
        row.decode.frame.estimatedSnrDb?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(-50, 99),
        if (Math.floorMod(index, 2L) == 0L) SlotPeriod.ODD else SlotPeriod.EVEN)
}

/** FT8/NONE templates ported from pinned MainWindow::genCQMsg/genStdMsgs.
 * This is message generation only; selection and QSO progression remain entirely manual.
 */
fun manualMessages(station: StationIdentity, peer: String, report: Int): Map<ManualMessage, String> {
    val own = station.normalized()
    require(own.isValid) { "Save a complete CB callsign and locator in Station first." }
    require(report in -50..99) { "Report must be between -50 and +99 dB." }
    val his = CbCallsign.normalize(peer)
    require(his.isEmpty() || CbCallsign.isValid(his) && his != own.callsign) { "Enter a complete CB peer different from your own callsign." }
    return standardManualTemplates(own.callsign, own.grid, his, report)
}

/** Rendering shared with the desktop; the public entry point enforces CB identities. */
internal fun standardManualTemplates(ownCall: String, grid: String, his: String, report: Int): Map<ManualMessage, String> {
    val standard = Regex("^(?:[A-Z]{0,2}|[A-Z][0-9]|[0-9][A-Z])[0-9][A-Z]{0,3}(?:/R|/P)?$")
    val myStd = standard.matches(ownCall)
    val result = linkedMapOf(ManualMessage.CQ to "CQ $ownCall${if (myStd) " ${grid.take(4)}" else ""}")
    if (his.isEmpty()) return result
    val hisStd = standard.matches(his)
    val rpt = String.format(Locale.ROOT, "%+03d", report)
    val plain = "$his $ownCall"
    val firstHashed = "<$his> $ownCall"
    val secondHashed = "$his <$ownCall>"
    val longPair = !myStd && !hisStd
    result[ManualMessage.REPLY] = when {
        longPair || !myStd -> firstHashed
        !hisStd -> "$firstHashed ${grid.take(4)}"
        else -> "$plain ${grid.take(4)}"
    }
    val reports = when { longPair -> his; !hisStd -> firstHashed; !myStd -> secondHashed; else -> plain }
    result[ManualMessage.REPORT] = "$reports $rpt"
    result[ManualMessage.ROGER] = "$reports R$rpt"
    val finals = when { longPair -> his; !hisStd -> secondHashed; !myStd -> firstHashed; else -> plain }
    result[ManualMessage.RR73] = "$finals RR73"
    result[ManualMessage.SIGNOFF] = "$finals 73"
    return result
}

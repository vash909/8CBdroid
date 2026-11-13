package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.logbook.*
import net.vash.ft8cbdroid.core.qso.*
import net.vash.ft8cbdroid.core.message.ManualMessage
import net.vash.ft8cbdroid.core.context.*
import org.junit.Assert.*
import org.junit.Test

class QsoLogTest {
    private val station = StationIdentity("1AT106", "JN44")
    private val model = CbQsoProgression(station)
    private var ids = 0
    private val tracker = QsoCompletionTracker { "exchange-${++ids}" }
    private val completions = mutableListOf<QsoCompletion>()
    private fun observe(slot: Long) { tracker.observe(station, model.snapshot, slot, 27_265_000, 1500)?.let(completions::add) }
    private fun send(kind: ManualMessage, slot: Long, report: Int? = null) {
        assertEquals(CbQsoDisposition.APPLIED, model.dispatch(CbQsoEvent.Prepare(kind, report)).disposition)
        val ticket = requireNotNull(model.snapshot.pending)
        assertEquals(CbQsoDisposition.APPLIED, model.dispatch(CbQsoEvent.Sent(ticket)).disposition); observe(slot)
    }
    private fun receive(kind: CbQsoMessage, slot: Long, report: Int? = null) {
        assertEquals(CbQsoDisposition.APPLIED, model.dispatch(CbQsoEvent.Received(CbQsoReception(
            CbQsoOrigin.NamedSender("26AT101"), "1AT106", kind, report))).disposition); observe(slot)
    }
    private fun exchange(start: Long = 0) {
        model.dispatch(CbQsoEvent.SelectPeer("26AT101"))
        send(ManualMessage.REPLY, start)
        receive(CbQsoMessage.REPORT, start + 15000, -9)
        send(ManualMessage.ROGER, start + 30000, -7)
        receive(CbQsoMessage.RR73, start + 45000)
        assertTrue(completions.isEmpty())
        send(ManualMessage.SIGNOFF, start + 60000)
    }
    @Test fun onlyCompleteExchangeYieldsOneReviewCandidateWithActualEventTimes() {
        exchange(15000)
        val c = completions.single()
        assertEquals("26AT101", c.details.peerCall); assertEquals("JN44", c.details.ownGrid)
        assertEquals(15000L, c.details.startedUtcMillis); assertEquals(90000L, c.details.endedUtcMillis)
        assertEquals(-7, c.details.sentReport); assertEquals(-9, c.details.receivedReport)
        observe(120000); observe(135000); assertEquals(1, completions.size)
        assertTrue(QsoLogSnapshot(pending = completions).workedCalls("1AT106").isEmpty())
    }
    @Test fun abortAndLateTicketsCannotCreateContacts() {
        model.dispatch(CbQsoEvent.SelectPeer("26AT101")); send(ManualMessage.REPLY, 0)
        model.dispatch(CbQsoEvent.Prepare(ManualMessage.REPORT, -7)); val old = requireNotNull(model.snapshot.pending)
        model.dispatch(CbQsoEvent.Abort); observe(15000)
        assertEquals(CbQsoDisposition.REJECTED, model.dispatch(CbQsoEvent.Sent(old)).disposition)
        observe(30000); assertTrue(completions.isEmpty())
        tracker.reset(); model.dispatch(CbQsoEvent.Reset); exchange(60000)
        assertEquals(60000L, completions.single().details.startedUtcMillis)
    }
    @Test fun workHistoryUsesSavedRecordsForTheCurrentStationAndExplicitSendersOnly() {
        val detail = QsoDetails("1AT106", "26AT101", 0, 15000, dialHz = 27_265_000)
        val saved = QsoRecord("manual-1", null, 1, QsoOrigin.MANUAL, detail)
        val foreign = saved.copy(id = "manual-2", details = detail.copy(ownCall = "161AT101", peerCall = "999ZZ999"))
        val snapshot = QsoLogSnapshot(listOf(saved, foreign))
        assertEquals(setOf("26AT101"), snapshot.workedCalls(" 1at106 "))
        val worked = snapshot.workedCalls("1AT106")
        assertTrue(CbOperatingContext.fromDesktopText("CQ 26AT101").matches(ActivityFilter.WORKED, "1AT106", worked))
        assertFalse(CbOperatingContext.fromDesktopText("1AT106 RR73").matches(ActivityFilter.WORKED, "1AT106", worked))
        assertFalse(CbOperatingContext.fromDesktopText("CQ 999ZZ999").matches(ActivityFilter.WORKED, "1AT106", worked))
        assertEquals(listOf(foreign), snapshot.search("999zz999"))
    }
    @Test fun validationKeepsCbIdentityAndManualFrequencyExplicit() {
        val d = QsoDetails(" 1at106 ", "26at/zz", 0, 15000, "jn44", dialHz = 27_265_000).normalized()
        d.validate(); assertEquals("26AT/ZZ", d.peerCall)
        for (bad in listOf(d.copy(peerCall = "K1ABC"), d.copy(peerCall = d.ownCall), d.copy(endedUtcMillis = -1),
            d.copy(peerGrid = "ZZ00"), d.copy(dialHz = 0), d.copy(sentReport = -51), d.copy(notes = "x".repeat(501))))
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
    }
}

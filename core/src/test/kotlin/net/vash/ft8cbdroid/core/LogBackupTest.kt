package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.logbook.*
import org.junit.Assert.*
import org.junit.Test

class LogBackupTest {
    private val details=QsoDetails("1AT106","26AT101",12345,67890,dialHz=27_265_000,sentReport=-7,receivedReport=3,notes="Café 😎\nline two")
    private val local=QsoRecord("local","completion",3,QsoOrigin.LOCAL_AUDIO,details,1500,QsoTimeBasis.CORRECTED_APP_UTC)
    private val pending=QsoCompletion("pending",details,1000)
    @Test fun fullBackupPreservesExactIdentitiesVersionsMillisecondsNotesAndPending() {
        val snapshot=QsoLogSnapshot(listOf(local),listOf(pending));assertEquals(snapshot,LogBackup.decode(LogBackup.encode(snapshot)))
        assertEquals(QsoLogSnapshot(),LogBackup.decode(LogBackup.encode(QsoLogSnapshot())))
    }
    @Test fun damageTruncationTrailingBytesAndDuplicateIdentitiesAreRejected() {
        val bytes=LogBackup.encode(QsoLogSnapshot(listOf(local)))
        for(b in listOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0),bytes.copyOf().apply{this[20]=(this[20].toInt() xor 1).toByte()}))assertTrue(runCatching{LogBackup.decode(b)}.isFailure)
        assertTrue(runCatching{LogBackup.encode(QsoLogSnapshot(listOf(local,local)))}.isFailure)
        assertTrue(runCatching{LogBackup.encode(QsoLogSnapshot(listOf(local),listOf(pending.copy(id="completion"))))}.isFailure)
    }
    @Test fun duplicateReviewAndConcurrentChangesCannotOverwriteAnExistingLog() {
        val a=local.copy(id="manual",completionId=null,origin=QsoOrigin.MANUAL,txAudioHz=null,timeBasis=QsoTimeBasis.OPERATOR_UTC)
        val b=a.copy(id="import",origin=QsoOrigin.ADIF_IMPORT,timeBasis=QsoTimeBasis.IMPORTED_UTC)
        val current=QsoLogSnapshot(listOf(a));val imported=QsoLogSnapshot(listOf(b))
        assertEquals(LogEntryStatus.DUPLICATE,logMergeRows(current,imported).single().status)
        assertTrue(runCatching{mergeLog(LogMergeCommand(current,imported),current)}.isFailure)
        val command=LogMergeCommand(current,imported,setOf(b.id));val merged=mergeLog(command,current)
        assertEquals(2,merged.records.size);assertEquals(merged,mergeLog(command,merged))
        assertTrue(runCatching{mergeLog(command,current.copy(pending=listOf(pending)))}.isFailure)
    }
    @Test fun backupRestorationPreservesGenuineSimilarContactsAndPendingAndSkipsIdenticalIds() {
        val a=local.copy(id="a");val b=local.copy(id="b",completionId="other")
        val restore=QsoLogSnapshot(listOf(a,b),listOf(pending));val empty=QsoLogSnapshot()
        val merged=mergeLog(LogMergeCommand(empty,restore,checkDuplicates=false),empty)
        assertEquals(restore,merged);assertEquals(restore,mergeLog(LogMergeCommand(empty,restore,checkDuplicates=false),merged))
        assertTrue(logMergeRows(restore,restore,false).all{it.status==LogEntryStatus.EXISTING})
        assertEquals(LogEntryStatus.CONFLICT,logMergeRows(restore,QsoLogSnapshot(listOf(a.copy(version=4))),false).single().status)
    }
}

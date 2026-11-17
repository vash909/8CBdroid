package net.vash.ft8cbdroid.logbook

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.logbook.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogTransferViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val record = QsoRecord("fixture", null, 1, QsoOrigin.MANUAL,
        QsoDetails("1AT106", "26AT101", 15000, 90000, dialHz = 27_265_125, notes = "Portable"))
    private class Repository : QsoLogRepository {
        var value = QsoLogSnapshot(); var gate: CompletableDeferred<Unit>? = null
        var failAfterMerge = false; var merges = 0
        override suspend fun read() = value
        override suspend fun stage(completion: QsoCompletion) = error("Unused")
        override suspend fun save(command: SaveQso): QsoRecord = error("Unused")
        override suspend fun merge(command: LogMergeCommand): QsoLogSnapshot {
            merges++; gate?.await(); value = mergeLog(command, value)
            if (failAfterMerge) throw IOException("Read-back unavailable")
            return value
        }
    }
    private class Documents : LogDocuments {
        var bytes = byteArrayOf(); var written: ByteArray? = null; var failWrite = false
        var readGate: CompletableDeferred<Unit>? = null; var ignoreCancellation = false
        override suspend fun read(document: String): ByteArray {
            if (ignoreCancellation) withContext(NonCancellable) { readGate?.await() } else readGate?.await()
            return bytes
        }
        override suspend fun write(document: String, bytes: ByteArray) {
            if (failWrite) throw IOException("Destination unavailable")
            written = bytes.copyOf()
        }
        override suspend fun share(bytes: ByteArray): String { written = bytes; return "content://shared/fixture" }
    }
    private fun model(repo: Repository, documents: Documents): LogTransferViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, viewModelFactory { initializer {
            LogTransferViewModel(repo, documents, { "1AT106" }, {}, dispatcher)
        } })[LogTransferViewModel::class.java]
    }
    private fun import(model: LogTransferViewModel, kind: LogFileKind = LogFileKind.ADIF) {
        model.choose(kind); model.requestLaunched(requireNotNull(model.uiState.value.request).id); model.document("content://fixture")
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    @Test fun previewNeverWritesAndDuplicatesNeedAnExplicitSelectionAndReview() = runTest(dispatcher) {
        val repo = Repository().also { it.value = QsoLogSnapshot(listOf(record)) }
        val docs = Documents().also { it.bytes = Adif.encode(listOf(record), AdifBandPolicy.WSJT_CB_11M) }
        val model = model(repo, docs); import(model); runCurrent()
        assertEquals(LogEntryStatus.DUPLICATE, model.uiState.value.preview!!.rows.single().status)
        assertTrue(model.uiState.value.selected.isEmpty()); model.reviewed(true); model.apply(); runCurrent()
        assertEquals(0, repo.merges)
        val id = model.uiState.value.preview!!.rows.single().id
        model.select(id, true); assertFalse(model.uiState.value.reviewed)
        model.apply(); runCurrent(); assertEquals(0, repo.merges)
        model.reviewed(true); model.apply(); model.apply(); runCurrent()
        assertEquals(1, repo.merges); assertEquals(2, repo.value.records.size)
        assertEquals(QsoOrigin.ADIF_IMPORT, repo.value.records.last().origin)
        assertNull(repo.value.records.last().txAudioHz)
    }
    @Test fun cancelledOrMalformedFilesAndLateIoCannotReplaceANewerPreview() = runTest(dispatcher) {
        val repo = Repository(); val docs = Documents().also { it.bytes = "<call:99>broken".toByteArray() }
        val model = model(repo, docs); import(model); runCurrent()
        assertNotNull(model.uiState.value.error); assertNull(model.uiState.value.preview); assertEquals(0, repo.merges)
        docs.bytes = Adif.encode(listOf(record), AdifBandPolicy.WSJT_CB_11M)
        docs.readGate = CompletableDeferred(); docs.ignoreCancellation = true
        import(model); runCurrent(); model.cancel(); docs.readGate!!.complete(Unit); runCurrent()
        assertNull(model.uiState.value.preview); assertFalse(model.uiState.value.busy); assertEquals(0, repo.merges)
        model.choose(LogFileKind.ADIF); model.document(null); runCurrent()
        assertNull(model.uiState.value.preview); assertEquals(QsoLogSnapshot(), repo.value)
    }
    @Test fun failedExportRetriesTheFrozenSnapshotEvenAfterLogAndPolicyChanges() = runTest(dispatcher) {
        val repo = Repository().also { it.value = QsoLogSnapshot(listOf(record)) }
        val docs = Documents().also { it.failWrite = true }; val model = model(repo, docs)
        model.prepareExport(LogFileKind.ADIF); runCurrent(); assertTrue(model.uiState.value.choosing)
        model.document("content://denied"); runCurrent(); assertTrue(model.uiState.value.retryExport)
        repo.value = QsoLogSnapshot(); model.policy(AdifBandPolicy.FREQUENCY_ONLY); docs.failWrite = false
        model.retryExport(); model.document("content://good"); runCurrent()
        assertTrue(requireNotNull(docs.written).contentEquals(Adif.encode(listOf(record), AdifBandPolicy.WSJT_CB_11M)))
        assertFalse(model.uiState.value.retryExport)
        model.prepareExport(LogFileKind.BACKUP); runCurrent(); model.document(null)
        assertEquals(QsoLogSnapshot(), repo.value)
    }
    @Test fun staleReviewMustRefreshAndAcceptedMergeSurvivesTeardownWithIdempotentRetry() = runTest(dispatcher) {
        val repo = Repository(); val docs = Documents().also { it.bytes = LogBackup.encode(QsoLogSnapshot(listOf(record))) }
        val model = model(repo, docs); import(model, LogFileKind.BACKUP); runCurrent()
        repo.value = QsoLogSnapshot(listOf(record.copy(id = "other", details = record.details.copy(peerCall = "14AT101"))))
        model.reviewed(true); model.apply(); runCurrent()
        assertNotNull(model.uiState.value.error); assertEquals(1, repo.value.records.size)
        model.refreshPreview(); runCurrent(); assertFalse(model.uiState.value.reviewed)
        repo.failAfterMerge = true; model.reviewed(true); model.apply(); runCurrent()
        assertNotNull(model.uiState.value.error); assertEquals(2, repo.value.records.size)
        repo.failAfterMerge = false; repo.gate = CompletableDeferred()
        model.apply(); model.cancel(); stores.single().clear(); runCurrent()
        assertTrue(model.uiState.value.committing)
        repo.gate!!.complete(Unit); runCurrent()
        assertFalse(model.uiState.value.committing); assertNull(model.uiState.value.preview)
        assertEquals(2, repo.value.records.size)
    }
    @Test fun backupPreviewPreservesDistinctIdentitiesAndPendingItemsAndSharingUsesSavedOnly() = runTest(dispatcher) {
        val repo = Repository().also { it.value = QsoLogSnapshot(listOf(record), listOf(QsoCompletion("pending", record.details.copy(sentReport = -5, receivedReport = -7), 1500))) }
        val docs = Documents(); val model = model(repo, docs)
        model.prepareExport(LogFileKind.BACKUP); runCurrent(); model.document("content://backup"); runCurrent()
        assertEquals(repo.value, LogBackup.decode(requireNotNull(docs.written)))
        docs.bytes = requireNotNull(docs.written); repo.value = QsoLogSnapshot()
        import(model, LogFileKind.BACKUP); runCurrent()
        assertEquals(2, model.uiState.value.selected.size)
        model.reviewed(true); model.apply(); runCurrent()
        assertEquals(1, repo.value.records.size); assertEquals(1, repo.value.pending.size)
        model.prepareExport(LogFileKind.ADIF, true); runCurrent()
        assertEquals("content://shared/fixture", model.uiState.value.shareUri)
        assertEquals(1, Adif.decode(requireNotNull(docs.written)).records.size)
        model.shared(); assertNull(model.uiState.value.shareUri)
    }
}

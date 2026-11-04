package net.vash.ft8cbdroid.settings

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.vash.ft8cbdroid.core.StationIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationSettingsViewModelTest {
    private val saved = StationIdentity("1AT106", "JN45")

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsSavedIdentityWithoutMarkingItDirty() = runTest {
        val viewModel = StationSettingsViewModel(FakeRepository(saved))
        advanceUntilIdle()
        assertEquals(saved, viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.isSaved)
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test fun cannotOverwriteSettingsBeforeInitialReadCompletes() = runTest {
        val repository = FakeRepository(saved).apply { readGate = CompletableDeferred() }
        val viewModel = StationSettingsViewModel(repository)
        viewModel.changeCallsign("26AT101")
        viewModel.save()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(0, repository.writes)
        repository.readGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(saved, viewModel.uiState.value.draft)
    }

    @Test fun editsRemainUnsavedUntilSaveIsRequested() = runTest {
        val repository = FakeRepository(saved)
        val viewModel = StationSettingsViewModel(repository)
        advanceUntilIdle()
        viewModel.changeCallsign("26AT101")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canSave)
        assertEquals(saved, repository.stored)
        assertEquals(0, repository.writes)
    }

    @Test fun savesNormalizedIdentityAndReloadsItInANewViewModel() = runTest {
        val repository = FakeRepository(saved)
        val viewModel = StationSettingsViewModel(repository)
        advanceUntilIdle()
        viewModel.changeCallsign(" 26at101 ")
        viewModel.changeGrid(" io91ab ")
        viewModel.save()
        advanceUntilIdle()
        val expected = StationIdentity("26AT101", "IO91AB")
        assertEquals(expected, repository.stored)
        assertEquals(expected, viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.isSaved)
        val reopened = StationSettingsViewModel(repository)
        advanceUntilIdle()
        assertEquals(expected, reopened.uiState.value.draft)
    }

    @Test fun refusesInvalidOrUnchangedIdentity() = runTest {
        val repository = FakeRepository(saved)
        val viewModel = StationSettingsViewModel(repository)
        advanceUntilIdle()
        viewModel.save()
        viewModel.changeCallsign("1AT")
        viewModel.save()
        viewModel.changeCallsign("26AT101")
        viewModel.changeGrid("SS99")
        viewModel.save()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canSave)
        assertEquals(0, repository.writes)
    }

    @Test fun failedSaveKeepsDraftAndAllowsRetry() = runTest {
        val repository = FakeRepository(saved).apply { failSave = true }
        val viewModel = StationSettingsViewModel(repository)
        advanceUntilIdle()
        viewModel.changeCallsign("26AT101")
        viewModel.save()
        advanceUntilIdle()
        assertEquals(SettingsFailure.SAVE, viewModel.uiState.value.failure)
        assertEquals("26AT101", viewModel.uiState.value.draft.callsign)
        assertEquals(saved, repository.stored)
        assertTrue(viewModel.uiState.value.canSave)
        repository.failSave = false
        viewModel.save()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.failure)
        assertTrue(viewModel.uiState.value.isSaved)
    }

    @Test fun failedReadBlocksEditingUntilSuccessfulRetry() = runTest {
        val repository = FakeRepository(saved).apply { failRead = true }
        val viewModel = StationSettingsViewModel(repository)
        advanceUntilIdle()
        assertEquals(SettingsFailure.READ, viewModel.uiState.value.failure)
        assertFalse(viewModel.uiState.value.canEdit)
        viewModel.changeCallsign("26AT101")
        viewModel.save()
        assertEquals(0, repository.writes)
        repository.failRead = false
        viewModel.retryLoad()
        advanceUntilIdle()
        assertEquals(saved, viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.canEdit)
    }

    @Test fun inFlightSaveBlocksDuplicateWritesAndEdits() = runTest {
        val repository = FakeRepository(saved).apply { saveGate = CompletableDeferred() }
        val viewModel = StationSettingsViewModel(repository)
        advanceUntilIdle()
        viewModel.changeCallsign("26AT101")
        viewModel.save()
        viewModel.save()
        viewModel.changeCallsign("1AT999")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSaving)
        assertFalse(viewModel.uiState.value.canEdit)
        assertEquals(1, repository.writes)
        repository.saveGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("26AT101", repository.stored.callsign)
    }

    private class FakeRepository(var stored: StationIdentity) : StationPreferencesRepository {
        var writes = 0
        var failRead = false
        var failSave = false
        var readGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        override val identity = flow {
            readGate?.await()
            if (failRead) throw IOException("read failed")
            emit(stored)
        }
        override suspend fun save(identity: StationIdentity) {
            writes++
            saveGate?.await()
            if (failSave) throw IOException("write failed")
            stored = identity
        }
    }
}

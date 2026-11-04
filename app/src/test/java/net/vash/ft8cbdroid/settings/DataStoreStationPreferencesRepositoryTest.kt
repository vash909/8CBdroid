package net.vash.ft8cbdroid.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.core.StationIdentity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreStationPreferencesRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun survivesClosingAndReopeningTheDataStoreFile() = runBlocking {
        val file = File(temporaryFolder.root, "station.preferences_pb")
        withRepository(file) { repository ->
            assertEquals(StationIdentity(), repository.identity.first())
            repository.save(StationIdentity(" 1at106 ", " jn45ab "))
        }
        assertTrue(file.isFile)
        // A new DataStore instance reads the on-disk result, not a cached in-memory value.
        withRepository(file) { reopened ->
            assertEquals(StationIdentity("1AT106", "JN45AB"), reopened.identity.first())
        }
    }

    @Test fun invalidSaveDoesNotReplaceThePreviouslySavedPair() = runBlocking {
        withRepository(File(temporaryFolder.root, "station.preferences_pb")) { repository ->
            val saved = StationIdentity("1AT106", "JN45")
            repository.save(saved)
            try {
                repository.save(StationIdentity("26AT101", "INVALID"))
                fail("Expected invalid locator to be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals(saved, repository.identity.first())
            }
        }
    }

    private suspend fun withRepository(
        file: File,
        test: suspend (DataStoreStationPreferencesRepository) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            test(DataStoreStationPreferencesRepository(dataStore))
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }
}

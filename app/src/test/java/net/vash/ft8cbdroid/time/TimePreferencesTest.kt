package net.vash.ft8cbdroid.time

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.vash.ft8cbdroid.core.time.TimeSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimePreferencesTest {
    @get:Rule val folder = TemporaryFolder()
    private suspend fun withRepository(file: File, block: suspend (DataStoreTimePreferencesRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { block(DataStoreTimePreferencesRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
    @Test fun customHostnameAndPresetSelectionSurviveActualFileReopening() = runBlocking {
        val file = File(folder.root, "time.preferences_pb")
        withRepository(file) { it.save(TimeSettings("custom", "NTP.Example.Org.")) }
        withRepository(file) {
            assertEquals(TimeSettings("custom", "ntp.example.org"), it.settings.first())
            it.save(it.settings.first().copy(presetId = "asia"))
        }
        withRepository(file) { assertEquals(TimeSettings("asia", "ntp.example.org"), it.settings.first()) }
    }
    @Test fun invalidServerNeverReplacesSavedConfiguration() = runBlocking {
        withRepository(File(folder.root, "time.preferences_pb")) {
            it.save(TimeSettings("custom", "192.168.1.1"))
            try { it.save(TimeSettings("custom", "https://bad")); fail() } catch (_: IllegalArgumentException) { }
            assertEquals("192.168.1.1", it.settings.first().host)
        }
    }
}

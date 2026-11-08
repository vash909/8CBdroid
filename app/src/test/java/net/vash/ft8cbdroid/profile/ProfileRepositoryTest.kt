package net.vash.ft8cbdroid.profile

import androidx.datastore.core.DataStoreFactory
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.vash.ft8cbdroid.core.ConnectionMode
import net.vash.ft8cbdroid.core.profile.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private suspend fun withRepository(file: File, test: suspend (DataStoreProfileRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { test(DataStoreProfileRepository(DataStoreFactory.create(serializer = ProfileSerializer, scope = scope, produceFile = { file }))) }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
    @Test fun previousInstallationGainsDefaultWithoutChangingExistingStationOrTimeFiles() = runBlocking {
        val station = File(folder.root, "station.preferences_pb").apply { writeBytes(byteArrayOf(1,2,3)) }
        val time = File(folder.root, "time.preferences_pb").apply { writeBytes(byteArrayOf(4,5,6)) }
        withRepository(File(folder.root, "profiles.bin")) { repository ->
            assertEquals(ProfileCollection(), repository.profiles.first())
            repository.save(OperatingProfile(name = "Portable"))
        }
        assertArrayEquals(byteArrayOf(1,2,3), station.readBytes()); assertArrayEquals(byteArrayOf(4,5,6), time.readBytes())
    }
    @Test fun saveSelectAndDeleteSurviveClosingAndReopeningTheActualFile() = runBlocking {
        val file = File(folder.root, "profiles.bin")
        val lan = OperatingProfile(id = "lan", name = "Desktop", mode = ConnectionMode.WSJT_CB_LAN,
            lan = ProfileLan(2345, "192.0.2.10"), audioOutput = ProfileAudioRoute.USB)
        withRepository(file) { it.save(lan); it.activate("lan") }
        withRepository(file) {
            assertEquals(lan, it.profiles.first().active)
            it.activate("default"); it.delete("lan")
        }
        withRepository(file) { assertEquals(ProfileCollection(), it.profiles.first()) }
    }
    @Test fun rejectedMutationPreservesPreviousBytes() = runBlocking {
        val file = File(folder.root, "profiles.bin")
        withRepository(file) {
            it.save(OperatingProfile(name = "Portable"))
            val bytes = file.readBytes()
            assertTrue(runCatching { it.save(OperatingProfile(name = "")) }.isFailure)
            assertTrue(runCatching { it.activate("missing") }.isFailure)
            assertTrue(runCatching { it.delete("default") }.isFailure)
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun unknownVersionAndCorruptionNeverFallBackToDefaultsOrOverwriteTheFile() = runBlocking {
        for (bytes in listOf(byteArrayOf(1,2,3), ProfileCodec.encode(ProfileCollection()).also { it[7] = 99 })) {
            val file = File(folder.root, "profiles.bin").apply { writeBytes(bytes) }
            withRepository(file) {
                assertTrue(runCatching { it.profiles.first() }.isFailure)
                assertTrue(runCatching { it.save(OperatingProfile(name = "Replacement")) }.isFailure)
            }
            assertArrayEquals(bytes, file.readBytes())
        }
    }
}

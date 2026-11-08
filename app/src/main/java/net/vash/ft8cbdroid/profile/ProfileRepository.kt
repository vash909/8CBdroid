package net.vash.ft8cbdroid.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.Flow
import net.vash.ft8cbdroid.core.profile.*
import java.io.InputStream
import java.io.OutputStream

// Introducing profiles migrates an installation with no profile file to Default; station/time stores stay intact.
val Context.operatingProfiles: DataStore<ProfileCollection> by dataStore(fileName = "operating-profiles.bin", serializer = ProfileSerializer)
object ProfileSerializer : Serializer<ProfileCollection> {
    override val defaultValue = ProfileCollection()
    override suspend fun readFrom(input: InputStream) = ProfileCodec.read(input)
    override suspend fun writeTo(t: ProfileCollection, output: OutputStream) { output.write(ProfileCodec.encode(t)) }
}
interface ProfileRepository {
    val profiles: Flow<ProfileCollection>
    suspend fun save(profile: OperatingProfile): ProfileCollection
    suspend fun activate(id: String): ProfileCollection
    suspend fun delete(id: String): ProfileCollection
}
class DataStoreProfileRepository(private val store: DataStore<ProfileCollection>) : ProfileRepository {
    override val profiles = store.data
    override suspend fun save(profile: OperatingProfile) = store.updateData { it.save(profile) }
    override suspend fun activate(id: String) = store.updateData { it.copy(activeId = id).validate() }
    override suspend fun delete(id: String) = store.updateData { it.delete(id) }
}

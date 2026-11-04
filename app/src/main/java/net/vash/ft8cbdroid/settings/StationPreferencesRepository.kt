package net.vash.ft8cbdroid.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.vash.ft8cbdroid.core.StationIdentity

// A single application-scoped DataStore instance, including after activity recreation.
val Context.stationPreferences: DataStore<Preferences> by preferencesDataStore(name = "station")

interface StationPreferencesRepository {
    val identity: Flow<StationIdentity>
    suspend fun save(identity: StationIdentity)
}

class DataStoreStationPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : StationPreferencesRepository {
    override val identity: Flow<StationIdentity> = dataStore.data.map { preferences ->
        StationIdentity(preferences[CALLSIGN].orEmpty(), preferences[GRID].orEmpty())
    }

    override suspend fun save(identity: StationIdentity) {
        val normalized = identity.normalized()
        require(normalized.isValid) { "A valid CB callsign and 4- or 6-character locator are required" }
        dataStore.edit { preferences ->
            preferences[CALLSIGN] = normalized.callsign
            preferences[GRID] = normalized.grid
        }
    }

    private companion object {
        val CALLSIGN = stringPreferencesKey("callsign")
        val GRID = stringPreferencesKey("grid")
    }
}

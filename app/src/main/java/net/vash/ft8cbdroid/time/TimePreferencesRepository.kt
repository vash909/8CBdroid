package net.vash.ft8cbdroid.time

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.vash.ft8cbdroid.core.time.TimeSettings

val Context.timePreferences: DataStore<Preferences> by preferencesDataStore(name = "time")
interface TimePreferencesRepository {
    val settings: Flow<TimeSettings>
    suspend fun save(settings: TimeSettings)
}
class DataStoreTimePreferencesRepository(private val store: DataStore<Preferences>) : TimePreferencesRepository {
    private val preset = stringPreferencesKey("ntp_preset")
    private val custom = stringPreferencesKey("ntp_custom_host")
    override val settings = store.data.map { TimeSettings(it[preset] ?: "global", it[custom].orEmpty()).validated() }
    override suspend fun save(settings: TimeSettings) {
        val valid = settings.validated()
        store.edit { it[preset] = valid.presetId; it[custom] = valid.customHost }
    }
}

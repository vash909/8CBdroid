package net.vash.ft8cbdroid.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.vash.ft8cbdroid.core.StationIdentity

enum class SettingsFailure { READ, SAVE }

data class StationSettingsUiState(
    val draft: StationIdentity = StationIdentity(),
    val saved: StationIdentity? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val failure: SettingsFailure? = null,
) {
    val canEdit: Boolean get() = saved != null && !isLoading && !isSaving
    val hasChanges: Boolean get() = draft.normalized() != saved
    val canSave: Boolean get() = canEdit && draft.isValid && hasChanges
    val isSaved: Boolean get() = saved?.isValid == true && !hasChanges && !isSaving
}

class StationSettingsViewModel(private val repository: StationPreferencesRepository) : ViewModel() {
    private val state = MutableStateFlow(StationSettingsUiState())
    val uiState = state.asStateFlow()

    init { load() }

    fun retryLoad() {
        if (!state.value.isLoading && state.value.failure == SettingsFailure.READ) load()
    }

    private fun load() {
        state.update { it.copy(isLoading = true, failure = null) }
        viewModelScope.launch {
            try {
                val saved = repository.identity.first()
                state.value = StationSettingsUiState(draft = saved, saved = saved, isLoading = false)
            } catch (_: IOException) {
                // Keep editing disabled rather than overwriting unread settings with defaults.
                state.update { it.copy(isLoading = false, failure = SettingsFailure.READ) }
            }
        }
    }

    fun changeCallsign(value: String) {
        if (state.value.canEdit) state.update {
            it.copy(draft = it.draft.copy(callsign = value.uppercase(Locale.ROOT)), failure = null)
        }
    }

    fun changeGrid(value: String) {
        if (state.value.canEdit) state.update {
            it.copy(draft = it.draft.copy(grid = value.uppercase(Locale.ROOT)), failure = null)
        }
    }

    fun save() {
        if (!state.value.canSave) return
        val identity = state.value.draft.normalized()
        state.update { it.copy(isSaving = true, failure = null) }
        viewModelScope.launch {
            try {
                repository.save(identity)
                state.update { it.copy(draft = identity, saved = identity, isSaving = false) }
            } catch (_: IOException) {
                state.update { it.copy(isSaving = false, failure = SettingsFailure.SAVE) }
            }
        }
    }

    companion object {
        fun factory(repository: StationPreferencesRepository) = viewModelFactory {
            initializer { StationSettingsViewModel(repository) }
        }
    }
}

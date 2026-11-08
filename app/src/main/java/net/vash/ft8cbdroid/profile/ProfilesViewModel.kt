package net.vash.ft8cbdroid.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.vash.ft8cbdroid.core.ConnectionMode
import net.vash.ft8cbdroid.core.profile.*
import net.vash.ft8cbdroid.core.radio.*
import java.util.UUID

/** Invalid numeric edits remain drafts and never reach a backend or the saved file. */
data class ProfileDraft(val value: OperatingProfile, val frequency: String = value.referenceFrequencyHz.toString(),
    val baud: String = value.serial.baud.toString(), val port: String = value.lan.port.toString()) {
    fun parsed(): OperatingProfile = value.copy(name = value.name.trim(),
        referenceFrequencyHz = frequency.toLongOrNull() ?: throw IllegalArgumentException("Enter a reference dial frequency in Hz."),
        serial = value.serial.copy(baud = baud.toIntOrNull() ?: throw IllegalArgumentException("Enter a serial rate in baud.")),
        lan = value.lan.copy(port = port.toIntOrNull() ?: throw IllegalArgumentException("Enter a UDP port."), desktopAddress = value.lan.desktopAddress.trim())).validate()
    val validation get() = runCatching { parsed() }.exceptionOrNull()?.message
}

interface ProfileRuntime {
    /** Must finish microphone/listener cleanup and invalidate timing before any configuration changes. */
    suspend fun stop()
    suspend fun configure(profile: OperatingProfile)
}

data class ProfilesUiState(
    val saved: ProfileCollection? = null, val draft: ProfileDraft? = null,
    val applied: OperatingProfile? = null, val revision: Int = 0, val receiveLan: Boolean = false,
    val busy: Boolean = true, val error: String? = null, val notice: String? = null,
    val catalog: RadioCatalogSnapshot? = null, val query: String = "",
) {
    val canEdit get() = saved != null && !busy
    val ready get() = applied != null && !busy
    val dirty get() = draft?.let { d -> runCatching { saved?.profiles?.find { it.id == d.value.id } != d.parsed() }.getOrDefault(true) } ?: false
    val canSave get() = canEdit && dirty && draft?.validation == null
    val canApply get() = canEdit && !dirty && draft != null
    val models get() = catalog?.models.orEmpty().filter { !it.isDummy && it.portType == 1 && it.matches(query) }
}

class ProfilesViewModel(private val repository: ProfileRepository, private val runtime: ProfileRuntime,
    private val radioCatalog: RadioCatalog) : ViewModel() {
    private val state = MutableStateFlow(ProfilesUiState())
    private var job: kotlinx.coroutines.Job? = null
    val uiState = state.asStateFlow()
    init { load() }
    fun load() {
        if (state.value.saved != null || (!state.value.busy && state.value.applied != null)) return
        work("Could not read operating profiles. Retry loading; saved data has been preserved.") {
            runtime.stop()
            val saved = repository.profiles.first().validate()
            state.value = state.value.copy(saved = saved, draft = ProfileDraft(saved.active))
            validateModel(saved.active)
            runtime.configure(saved.active)
            state.value = state.value.copy(applied = saved.active, revision = state.value.revision + 1, receiveLan = saved.active.mode == ConnectionMode.WSJT_CB_LAN,
                notice = "Profile restored. Start reception explicitly; TX is off.")
        }
    }
    fun temporarySource(lan: Boolean) {
        if (state.value.ready) state.value = state.value.copy(receiveLan = lan)
    }
    fun edit(next: ProfileDraft) {
        if (state.value.canEdit && next.value.id == state.value.draft?.value?.id)
            state.value = state.value.copy(draft = next, error = null, notice = null)
    }
    fun select(id: String) {
        if (!state.value.canEdit || state.value.dirty) return
        val profile = state.value.saved?.profiles?.find { it.id == id } ?: return
        state.value = state.value.copy(draft = ProfileDraft(profile), query = "", error = null, notice = null)
    }
    fun newProfile() {
        if (!state.value.canEdit || state.value.dirty || state.value.saved!!.profiles.size >= 32) return
        val used = state.value.saved!!.profiles.map { it.name.lowercase(java.util.Locale.ROOT) }
        val name = generateSequence(1) { it + 1 }.map { "Profile $it" }.first { it.lowercase(java.util.Locale.ROOT) !in used }
        state.value = state.value.copy(draft = ProfileDraft(OperatingProfile(id = UUID.randomUUID().toString(), name = name)), query = "", error = null, notice = null)
    }
    fun discard() {
        if (!state.value.canEdit) return
        val saved = state.value.saved!!
        val original = saved.profiles.find { it.id == state.value.draft?.value?.id } ?: saved.active
        state.value = state.value.copy(draft = ProfileDraft(original), error = null, notice = "Draft discarded.")
    }
    fun save() {
        if (!state.value.canSave) return
        val profile = state.value.draft!!.parsed()
        work("Could not save the profile. Your draft is retained; retry Save profile.") {
            validateModel(profile)
            val saved = repository.save(profile)
            state.value = state.value.copy(saved = saved, draft = ProfileDraft(profile), notice = "Profile saved. Apply it explicitly, or restore it on the next app start if it is active.")
        }
    }
    fun apply() {
        if (!state.value.canApply) return
        val profile = state.value.draft!!.parsed()
        state.value = state.value.copy(applied = null)
        work("Could not apply the profile. Reception is stopped; retry Apply profile.") {
            runtime.stop()
            validateModel(profile)
            val saved = repository.activate(profile.id)
            state.value = state.value.copy(saved = saved)
            runtime.configure(profile)
            state.value = state.value.copy(saved = saved, applied = profile, revision = state.value.revision + 1, receiveLan = profile.mode == ConnectionMode.WSJT_CB_LAN,
                notice = "Profile applied. Synchronize time and start reception explicitly; TX is off.")
        }
    }
    fun delete() {
        if (!state.value.canEdit || state.value.dirty) return
        val id = state.value.draft?.value?.id ?: return
        if (id == state.value.saved?.activeId) return
        work("Could not delete the profile. Retry Delete profile.") {
            val saved = repository.delete(id)
            state.value = state.value.copy(saved = saved, draft = ProfileDraft(saved.active), notice = "Profile deleted.")
        }
    }
    fun query(value: String) { if (state.value.canEdit) state.value = state.value.copy(query = value.take(80)) }
    fun loadModels() {
        if (!state.value.canEdit) return
        work("Could not load Hamlib models. Retry Load radio models.") { state.value = state.value.copy(catalog = radioCatalog.load()) }
    }
    fun chooseModel(id: Int) {
        if (!state.value.canEdit) return
        val catalog = state.value.catalog ?: return
        val model = catalog.models.find { it.id == id && !it.isDummy && it.portType == 1 } ?: return
        val draft = state.value.draft ?: return
        val baud = draft.baud.toIntOrNull()?.takeIf { it in model.baudMin..model.baudMax } ?: model.baudMin
        edit(draft.copy(value = draft.value.copy(radio = HamlibModelRef(id, catalog.version, "${model.manufacturer} ${model.name}")), baud = baud.toString()))
    }
    private suspend fun validateModel(profile: OperatingProfile) {
        if (profile.mode != ConnectionMode.USB_CAT) return
        val catalog = state.value.catalog ?: radioCatalog.load().also { state.value = state.value.copy(catalog = it) }
        profile.validateCatalog(catalog)
    }
    private fun work(failure: String, action: suspend () -> Unit) {
        if (job?.isActive == true) return
        state.value = state.value.copy(busy = true, error = null, notice = null)
        job = viewModelScope.launch {
            try { action() }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { state.value = state.value.copy(error = failure) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { state.value = state.value.copy(error = error.message ?: failure) }
            catch (_: Exception) { state.value = state.value.copy(error = failure) }
            catch (_: LinkageError) { state.value = state.value.copy(error = "Hamlib is unavailable on this device. Choose a phone-audio or LAN profile.") }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
}

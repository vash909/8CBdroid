package net.vash.ft8cbdroid.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import net.vash.ft8cbdroid.core.radio.*
import net.vash.ft8cbdroid.hamlib.NativeHamlibCatalog

enum class HamlibWork { CATALOG, DUMMY }
data class HamlibUiState(
    val catalog: RadioCatalogSnapshot? = null,
    val query: String = "",
    val selectedId: Int? = null,
    val busy: HamlibWork? = null,
    val dummy: DummyRadioCheck? = null,
    val error: String? = null,
) {
    val matches: List<RadioModel> get() = catalog?.models.orEmpty().filter { it.matches(query) }
    val selected: RadioModel? get() = catalog?.models?.firstOrNull { it.id == selectedId }
}

class HamlibViewModel(private val backend: RadioCatalog) : ViewModel() {
    constructor() : this(NativeHamlibCatalog())
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(HamlibUiState())
    val uiState: kotlinx.coroutines.flow.StateFlow<HamlibUiState> = mutable
    private var job: Job? = null
    private var generation = 0L
    fun query(value: String) { mutable.value = mutable.value.copy(query = value.take(80)) }
    fun select(id: Int) {
        if (mutable.value.catalog?.models?.any { it.id == id } == true)
            mutable.value = mutable.value.copy(selectedId = id)
    }
    fun load() = run(HamlibWork.CATALOG) {
        val catalog = backend.load()
        val update: (HamlibUiState) -> HamlibUiState = { it.copy(catalog = catalog, selectedId = null) }
        update
    }
    fun checkDummy() = run(HamlibWork.DUMMY) {
        val result = backend.checkDummy()
        check(result.passed) { "Unexpected Dummy result" }
        val update: (HamlibUiState) -> HamlibUiState = { it.copy(dummy = result) }
        update
    }
    fun cancel() {
        generation++
        job?.cancel(); job = null
        mutable.value = mutable.value.copy(busy = null)
    }
    private fun run(work: HamlibWork, action: suspend () -> ((HamlibUiState) -> HamlibUiState)) {
        if (mutable.value.busy != null) return
        val ticket = ++generation
        mutable.value = mutable.value.copy(busy = work, error = null,
            dummy = if (work == HamlibWork.DUMMY) null else mutable.value.dummy)
        job = viewModelScope.launch {
            try {
                val update = action()
                coroutineContext.ensureActive()
                if (generation == ticket) mutable.value = update(mutable.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == ticket) mutable.value = mutable.value.copy(error = failure(work))
            } catch (_: LinkageError) {
                if (generation == ticket) mutable.value = mutable.value.copy(error = "Hamlib is unavailable on this device.")
            } finally {
                if (generation == ticket) mutable.value = mutable.value.copy(busy = null)
            }
        }
    }
    private fun failure(work: HamlibWork) = when (work) {
        HamlibWork.CATALOG -> "Could not load the Hamlib catalog. Try again."
        HamlibWork.DUMMY -> "The software-only Dummy check failed. Try again."
    }
}

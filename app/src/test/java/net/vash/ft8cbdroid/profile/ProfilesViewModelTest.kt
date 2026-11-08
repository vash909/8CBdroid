package net.vash.ft8cbdroid.profile

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.ConnectionMode
import net.vash.ft8cbdroid.core.profile.*
import net.vash.ft8cbdroid.core.radio.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private class Repository : ProfileRepository {
        var value = ProfileCollection(); var failRead = false; var failWrite = false
        override val profiles: Flow<ProfileCollection> get() = flow { if (failRead) error("read"); emit(value) }
        override suspend fun save(profile: OperatingProfile): ProfileCollection {
            if (failWrite) error("write")
            value = value.save(profile); return value
        }
        override suspend fun activate(id: String): ProfileCollection {
            if (failWrite) error("write")
            value = value.copy(activeId = id).validate(); return value
        }
        override suspend fun delete(id: String): ProfileCollection {
            if (failWrite) error("write")
            value = value.delete(id); return value
        }
    }
    private class Runtime : ProfileRuntime {
        val events = mutableListOf<String>(); var cleanup: CompletableDeferred<Unit>? = null; var fail = false
        override suspend fun stop() { events += "stop"; cleanup?.await(); if (fail) error("stop failed") }
        override suspend fun configure(profile: OperatingProfile) { events += "configure:${profile.name}" }
    }
    private class Catalog : RadioCatalog {
        var fail = false
        var snapshot = RadioCatalogSnapshot("Hamlib 4.7.2", listOf(RadioModel(3073,"Icom","IC-7300","Stable",1,300,19200,true,true,true)))
        override suspend fun load(): RadioCatalogSnapshot { if (fail) error("catalog"); return snapshot }
        override suspend fun checkDummy() = error("Profiles must not open even a simulated rig")
    }
    private fun vm(repo: Repository, runtime: Runtime, catalog: Catalog = Catalog()): ProfilesViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider(store, viewModelFactory { initializer { ProfilesViewModel(repo, runtime, catalog) } })[ProfilesViewModel::class.java]
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    @Test fun startupRestoresOnlyConfigurationAfterCleanupAndNeverStartsAConnection() = runTest(dispatcher) {
        val runtime = Runtime().apply { cleanup = CompletableDeferred() }
        val vm = vm(Repository(), runtime); runCurrent()
        assertFalse(vm.uiState.value.ready); assertEquals(listOf("stop"), runtime.events)
        runtime.cleanup!!.complete(Unit); runCurrent()
        assertEquals(listOf("stop", "configure:Default"), runtime.events)
        assertTrue(vm.uiState.value.ready); assertFalse(vm.uiState.value.dirty)
    }
    @Test fun failedReadLocksEditingUntilExplicitRetry() = runTest(dispatcher) {
        val repo = Repository().apply { failRead = true }; val runtime = Runtime(); val vm = vm(repo,runtime); runCurrent()
        vm.newProfile(); assertFalse(vm.uiState.value.canEdit); assertFalse(vm.uiState.value.ready)
        repo.failRead = false; vm.load(); runCurrent()
        assertTrue(vm.uiState.value.canEdit); assertTrue(vm.uiState.value.ready)
    }
    @Test fun editsAndSaveFailuresKeepDraftAndDoNotApplyOrSwitchProfiles() = runTest(dispatcher) {
        val repo = Repository(); val runtime = Runtime(); val vm = vm(repo,runtime); runCurrent()
        vm.newProfile(); val draft = vm.uiState.value.draft!!
        vm.edit(draft.copy(value = draft.value.copy(name = "Portable"), port = "0"))
        assertFalse(vm.uiState.value.canSave); assertFalse(vm.uiState.value.canApply)
        vm.select("default"); assertEquals(draft.value.id, vm.uiState.value.draft!!.value.id)
        vm.edit(vm.uiState.value.draft!!.copy(port = "2240")); repo.failWrite = true; vm.save(); runCurrent()
        assertTrue(vm.uiState.value.dirty); assertNotNull(vm.uiState.value.error); assertEquals(1,repo.value.profiles.size)
        repo.failWrite = false; vm.save(); runCurrent()
        assertEquals(2,repo.value.profiles.size); assertFalse(vm.uiState.value.dirty)
        assertEquals(listOf("stop","configure:Default"),runtime.events)
        assertEquals("default",repo.value.activeId)
    }
    @Test fun applyWaitsForCleanupBlocksDuplicatesAndRequiresExplicitRetryAfterWriteFailure() = runTest(dispatcher) {
        val repo = Repository().apply { value = value.save(OperatingProfile("lan","Desktop",ConnectionMode.WSJT_CB_LAN)) }
        val runtime = Runtime(); val vm = vm(repo,runtime); runCurrent(); vm.select("lan")
        runtime.cleanup = CompletableDeferred(); vm.apply(); vm.apply(); runCurrent()
        assertFalse(vm.uiState.value.ready); assertFalse(vm.uiState.value.canEdit); assertEquals("default",repo.value.activeId)
        repo.failWrite = true; runtime.cleanup!!.complete(Unit); runCurrent()
        assertFalse(vm.uiState.value.ready); assertEquals("default",repo.value.activeId)
        assertEquals(1,runtime.events.count { it.startsWith("configure") })
        repo.failWrite = false; vm.apply(); runCurrent()
        assertEquals("lan",repo.value.activeId); assertEquals("Desktop",vm.uiState.value.applied!!.name)
        assertEquals("configure:Desktop",runtime.events.last()); assertTrue(vm.uiState.value.ready)
    }
    @Test fun cleanupFailureNeverConfiguresOrMarksTheProfileReady() = runTest(dispatcher) {
        val repo = Repository(); val runtime = Runtime(); val vm = vm(repo,runtime); runCurrent()
        runtime.fail = true; vm.apply(); runCurrent()
        assertFalse(vm.uiState.value.ready); assertEquals(listOf("stop","configure:Default","stop"),runtime.events)
        runtime.fail = false; vm.apply(); runCurrent(); assertTrue(vm.uiState.value.ready)
    }
    @Test fun discardAndDeleteProtectTheSavedAndActiveProfiles() = runTest(dispatcher) {
        val repo = Repository(); val runtime = Runtime(); val vm = vm(repo,runtime); runCurrent()
        vm.newProfile(); vm.discard(); assertEquals("default",vm.uiState.value.draft!!.value.id)
        vm.newProfile(); vm.save(); runCurrent(); val id = vm.uiState.value.draft!!.value.id
        vm.edit(vm.uiState.value.draft!!.copy(frequency = "invalid")); vm.discard()
        assertEquals(id,vm.uiState.value.draft!!.value.id); assertEquals("27265000",vm.uiState.value.draft!!.frequency)
        vm.delete(); runCurrent(); assertEquals(1,repo.value.profiles.size)
        vm.delete(); runCurrent(); assertEquals(1,repo.value.profiles.size)
    }
    @Test fun catalogVersionMismatchPreservesDataAndRequiresReselectingBeforeApply() = runTest(dispatcher) {
        val profile = OperatingProfile("usb","Radio",ConnectionMode.USB_CAT,radio = HamlibModelRef(3073,"Hamlib old","Icom IC-7300"))
        val repo = Repository().apply { value = ProfileCollection(listOf(profile),"usb") }
        val runtime = Runtime(); val catalog = Catalog(); val vm = vm(repo,runtime,catalog); runCurrent()
        assertFalse(vm.uiState.value.ready); assertTrue(vm.uiState.value.error!!.contains("version changed"))
        assertEquals(profile,repo.value.active); assertEquals(listOf("stop"),runtime.events)
        vm.chooseModel(3073); vm.save(); runCurrent(); vm.apply(); runCurrent()
        assertTrue(vm.uiState.value.ready); assertEquals("Hamlib 4.7.2",repo.value.active.radio!!.version)
    }
    @Test fun temporarySourceChangesSurviveOnlyTheCurrentViewModelAndNeverChangeTheSavedSetup() = runTest(dispatcher) {
        val repo = Repository(); val vm = vm(repo,Runtime()); runCurrent()
        vm.temporarySource(true); assertTrue(vm.uiState.value.receiveLan)
        assertEquals(ConnectionMode.PHONE_VOX,repo.value.active.mode)
        val reopened = vm(repo,Runtime()); runCurrent(); assertFalse(reopened.uiState.value.receiveLan)
        vm.apply(); runCurrent(); assertFalse(vm.uiState.value.receiveLan)
    }
    @Test fun catalogLoadFailureCanBeRetriedAndModelSelectionUsesDeclaredSerialRange() = runTest(dispatcher) {
        val catalog = Catalog().apply { fail = true }; val vm = vm(Repository(), Runtime(), catalog); runCurrent()
        vm.loadModels(); runCurrent(); assertNotNull(vm.uiState.value.error)
        catalog.fail = false; vm.loadModels(); runCurrent(); vm.query("IC-7300")
        assertEquals(1,vm.uiState.value.models.size); vm.chooseModel(3073)
        vm.edit(vm.uiState.value.draft!!.copy(value = vm.uiState.value.draft!!.value.copy(mode = ConnectionMode.USB_CAT), baud = "38400"))
        vm.save(); runCurrent(); assertTrue(vm.uiState.value.dirty); assertTrue(vm.uiState.value.error!!.contains("baud range"))
    }
}

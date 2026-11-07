package net.vash.ft8cbdroid.radio

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.vash.ft8cbdroid.core.radio.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HamlibViewModelTest {
    private class Fake : RadioCatalog {
        var loads = 0
        var checks = 0
        var fail = false
        var wait = false
        var ignoreCancellation = false
        val release = CompletableDeferred<Unit>()
        val row = RadioModel(3073, "Icom", "IC-7300", "Stable", 1, 300, 115200, true, true, false)
        override suspend fun load(): RadioCatalogSnapshot {
            loads++
            if (ignoreCancellation) withContext(NonCancellable) { release.await() }
            else if (wait) release.await()
            if (fail) error("Native failure")
            return RadioCatalogSnapshot("4.7.2", listOf(row))
        }
        override suspend fun checkDummy(): DummyRadioCheck {
            checks++
            if (fail) error("Dummy failure")
            return DummyRadioCheck(27_265_000, "USB", true)
        }
    }
    private fun setup(fake: Fake, store: ViewModelStore) = ViewModelProvider(store,
        viewModelFactory { initializer { HamlibViewModel(fake) } })[HamlibViewModel::class.java]
    private fun check(block: suspend TestScope.(Fake, HamlibViewModel, ViewModelStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore(); val fake = Fake()
        try { block(fake, setup(fake, store), store) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun explicitLoadAndDummySurviveOwnerReuseAndFilterWithoutOpeningHardware() = check { fake, vm, store ->
        assertEquals(0, fake.loads)
        vm.load(); runCurrent()
        assertEquals(1, fake.loads)
        vm.query("ic-7300"); vm.select(3073)
        assertEquals(fake.row, vm.uiState.value.selected)
        assertEquals(1, vm.uiState.value.matches.size)
        vm.select(999); assertEquals(3073, vm.uiState.value.selectedId)
        vm.query("missing"); assertTrue(vm.uiState.value.matches.isEmpty())
        assertSame(vm, setup(fake, store))
        vm.checkDummy(); runCurrent()
        assertTrue(vm.uiState.value.dummy!!.passed)
    }
    @Test fun duplicateWorkIsRejectedAndCancellationAllowsRetry() = check { fake, vm, _ ->
        fake.wait = true
        vm.load(); vm.load(); vm.checkDummy(); runCurrent()
        assertEquals(1, fake.loads); assertEquals(0, fake.checks)
        vm.cancel(); runCurrent()
        assertNull(vm.uiState.value.busy); assertNull(vm.uiState.value.error)
        fake.wait = false; vm.load(); runCurrent()
        assertEquals(2, fake.loads); assertNotNull(vm.uiState.value.catalog)
    }
    @Test fun lateNativeResultCannotReturnAfterCancellation() = check { fake, vm, _ ->
        fake.ignoreCancellation = true
        vm.load(); runCurrent(); vm.cancel()
        fake.release.complete(Unit); runCurrent()
        assertNull(vm.uiState.value.catalog); assertNull(vm.uiState.value.error)
    }
    @Test fun failureCanBeRetriedWithoutLosingTheExistingCatalog() = check { fake, vm, _ ->
        vm.load(); runCurrent(); fake.fail = true
        vm.load(); runCurrent()
        assertNotNull(vm.uiState.value.error); assertNotNull(vm.uiState.value.catalog)
        fake.fail = false; vm.load(); runCurrent()
        assertNull(vm.uiState.value.error)
        vm.checkDummy(); runCurrent(); assertTrue(vm.uiState.value.dummy!!.passed)
        fake.fail = true; vm.checkDummy(); runCurrent()
        assertNull(vm.uiState.value.dummy); assertNotNull(vm.uiState.value.error)
    }
    @Test fun clearingOwnerCancelsPendingWorkAndQueryIsBounded() = check { fake, vm, store ->
        fake.wait = true; vm.load(); runCurrent(); store.clear()
        fake.release.complete(Unit); runCurrent()
        assertNull(vm.uiState.value.catalog)
        vm.query("x".repeat(1000)); assertEquals(80, vm.uiState.value.query.length)
    }
}

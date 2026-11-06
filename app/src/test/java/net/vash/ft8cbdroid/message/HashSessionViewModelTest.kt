package net.vash.ft8cbdroid.message

import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.MessagePart
import net.vash.ft8cbdroid.core.message.PackResult
import org.junit.Assert.*
import org.junit.Test

class HashSessionViewModelTest {
    private fun payload(text: String) = (CbMessageCodec.pack(text) as PackResult.Encoded).payload

    @Test fun explicitReceiveAndCommitPreserveTheLastReceiveSnapshot() {
        val vm = HashSessionViewModel()
        vm.receive(payload("CQ 1AT106"))
        assertEquals(1, vm.uiState.value.pendingCount)
        vm.receive(payload("<1AT106> 26AT101"))
        assertEquals("<...> 26AT101", vm.uiState.value.message!!.text)
        vm.commit()
        assertEquals(2, vm.uiState.value.learnedCount)
        assertEquals("<...> 26AT101", vm.uiState.value.message!!.text)
        vm.receive(payload("<1AT106> 26AT101"))
        assertEquals("<1AT106> 26AT101", vm.uiState.value.message!!.text)
        vm.reset()
        assertNull(vm.uiState.value.message)
        assertEquals(0, vm.uiState.value.learnedCount)
    }

    @Test fun savedStationChangesResetSessionsButSameStationDoesNot() {
        val vm = HashSessionViewModel()
        vm.changeStation("1AT106")
        vm.receive(payload("CQ 26AT101"))
        vm.commit()
        vm.changeStation(" 1at106 ")
        assertEquals(1, vm.uiState.value.learnedCount)
        vm.changeStation("161XZ085")
        assertEquals(0, vm.uiState.value.learnedCount)
        assertNull(vm.uiState.value.message)
        vm.reset()
        assertEquals("161XZ085", vm.uiState.value.stationCall)
        vm.changeStation("")
        assertNull(vm.uiState.value.stationCall)
    }

    @Test fun collisionsAndUnsupportedFramesAreVisibleWithoutStaleSuccess() {
        val vm = HashSessionViewModel()
        for (call in listOf("1AT0002", "1AT0050")) {
            vm.receive(payload("CQ $call"))
            vm.commit()
        }
        vm.receive(payload("<1AT0002> 26AT101"))
        assertTrue(vm.uiState.value.message!!.parts[0] is MessagePart.AmbiguousCall)
        vm.receive(Ft8Payload77.fromBits("0".repeat(74) + "110"))
        assertNotNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.message)
        assertTrue(vm.uiState.value.learnedCount >= 2)
    }

    @Test fun newProcessModelStartsColdAndDoesNotPersistLearnedCalls() {
        val vm = HashSessionViewModel()
        vm.receive(payload("CQ 1AT106"))
        vm.commit()
        val fresh = HashSessionViewModel()
        fresh.receive(payload("<1AT106> 26AT101"))
        assertEquals("<...> 26AT101", fresh.uiState.value.message!!.text)
    }
}

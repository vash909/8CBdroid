package net.vash.ft8cbdroid.dsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.Ft8DecodeResult
import net.vash.ft8cbdroid.core.Ft8DecodedFrame
import net.vash.ft8cbdroid.core.Ft8Engine
import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.Ft8PcmSlot

/** Experimental bounded channel backend. Cancellation is observed between native calls. */
class NativeFt8Engine : Ft8Engine {
    override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float): Ft8PcmSlot =
        withContext(Dispatchers.Default) {
            gate.withLock { Ft8PcmSlot.fromSamples(NativeBridge.encode(payload.toByteArray(), audioFrequencyHz)) }
        }

    override suspend fun decode(slot: Ft8PcmSlot): Ft8DecodeResult = withContext(Dispatchers.Default) {
        gate.withLock {
            val result = NativeBridge.decode(slot.toFloatArray())
            Ft8DecodeResult(result.payloads.indices.map { i ->
                Ft8DecodedFrame(Ft8Payload77.fromBytes(result.payloads[i]), result.times[i],
                    result.frequencies[i], result.scores[i])
            }, result.candidates)
        }
    }

    companion object {
        // One native job across all backend instances; callers still own queue policy.
        private val gate = Mutex()
    }
}

internal class NativeResult(
    val payloads: Array<ByteArray>, val times: FloatArray, val frequencies: FloatArray,
    val scores: IntArray, val candidates: Int,
)

internal object NativeBridge {
    init { System.loadLibrary("eightcbdroid_ft8") }
    external fun encode(payload: ByteArray, frequency: Float): FloatArray
    external fun decode(pcm: FloatArray): NativeResult
}

package net.vash.ft8cbdroid.dsp

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.*

/** Process-wide bounded admission and cooperative native cancellation; no audio or radio access. */
class NativeFt8Engine : Ft8Engine {
    override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot =
        gate.run {
            withContext(Dispatchers.Default) {
                nativeCall { id -> Ft8PcmSlot.fromSamples(NativeBridge.encode(payload.toByteArray(), audioFrequencyHz, peakAmplitude, id)) }
            }
        }

    override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile): Ft8DecodeResult = gate.run {
        withContext(Dispatchers.Default) {
            val start = System.nanoTime()
            val result = nativeCall { id -> NativeBridge.decode(slot.toFloatArray(), profile.ordinal, id) }
            Ft8DecodeResult(result.payloads.indices.map { i ->
                Ft8DecodedFrame(Ft8Payload77.fromBytes(result.payloads[i]), result.times[i],
                    result.frequencies[i], result.scores[i], result.snrs[i].takeIf { it.isFinite() })
            }, result.candidates, profile, (System.nanoTime() - start) / 1_000_000, result.processed)
        }
    }

    private suspend fun <T> nativeCall(block: (Long) -> T): T = suspendCancellableCoroutine { continuation ->
        val id = NativeBridge.createCall()
        // This callback can execute on the cancelling thread while JNI blocks its worker.
        // Native IDs refer to locked shared ownership, so cancel/release races cannot free a live call.
        continuation.invokeOnCancellation { NativeBridge.cancelCall(id) }
        try {
            val result = block(id)
            if (continuation.isActive) continuation.resume(result)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            NativeBridge.releaseCall(id)
        }
    }

    companion object {
        private val gate = Ft8WorkGate()
        /** Diagnostic sampling only; stages are observational, not a synchronization contract. */
        fun workSnapshot(): NativeWorkSnapshot {
            val native = NativeBridge.workState()
            return NativeWorkSnapshot(gate.admittedCount, native[0], native[1])
        }
    }
}

data class NativeWorkSnapshot(val admitted: Int, val nativeCalls: Int, val stage: Int)

internal class NativeResult(
    val payloads: Array<ByteArray>, val times: FloatArray, val frequencies: FloatArray,
    val scores: IntArray, val snrs: FloatArray, val candidates: Int, val processed: Int,
)

internal object NativeBridge {
    init { System.loadLibrary("eightcbdroid_ft8") }
    external fun createCall(): Long
    external fun cancelCall(id: Long)
    external fun releaseCall(id: Long)
    external fun workState(): IntArray
    external fun encode(payload: ByteArray, frequency: Float, peakAmplitude: Float, id: Long): FloatArray
    external fun decode(pcm: FloatArray, profile: Int, id: Long): NativeResult
}

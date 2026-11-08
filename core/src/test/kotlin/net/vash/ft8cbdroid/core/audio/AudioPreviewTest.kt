package net.vash.ft8cbdroid.core.audio

import net.vash.ft8cbdroid.core.Ft8PcmSlot
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AudioPreviewTest {
    @Test fun gainAndRampsPreserveLengthSilenceAndImmutableSource() {
        val raw = FloatArray(180000) { if (it in 9600 until 161280) .4f else 0f }
        val slot = Ft8PcmSlot.fromSamples(raw)
        val output = previewSamples(slot, PreviewSettings(50, 20))
        assertEquals(180000, output.size)
        assertTrue(output.take(9600).all { it == 0f }); assertTrue(output.drop(161280).all { it == 0f })
        assertEquals(.2f, output[20000], .00001f)
        assertEquals(0f, output[9600]); assertEquals(0f, output[161279])
        assertTrue((9600 until 9840).all { output[it] <= output[it+1] })
        assertArrayEquals(raw, slot.toFloatArray(), 0f)
    }
    @Test fun boundedSettingsNeverAmplifyClipOrInventASignal() {
        val pcm = FloatArray(180000) { if (it % 2 == 0) 1f else -1f }
        for (ramp in listOf(5,20,100)) for (gain in listOf(1,50,100)) {
            val output = previewSamples(Ft8PcmSlot.fromSamples(pcm), PreviewSettings(gain,ramp))
            assertTrue(output.all { abs(it) <= gain / 100f })
        }
        assertTrue(previewSamples(Ft8PcmSlot.fromSamples(FloatArray(180000)),PreviewSettings()).all { it == 0f })
    }
    @Test fun invalidLevelsAndRampDurationsAreRejected() {
        for (pair in listOf(0 to 20, 101 to 20, 50 to 0, 50 to 101))
            assertTrue(runCatching { PreviewSettings(pair.first,pair.second) }.isFailure)
    }
}

package net.vash.ft8cbdroid.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpectrumTest {
    private fun tone(bin: Int, level: Float = .5f) = FloatArray(SpectrumFft.SIZE) {
        (level * sin(2 * PI * bin * it / SpectrumFft.SIZE)).toFloat()
    }
    @Test fun frequencyAxisAndHannGainMatchIndependentBinCenteredTones() {
        val fft = SpectrumFft()
        for (bin in listOf(68, 205, 341, 614, 1000)) {
            val power = fft.analyze(tone(bin))
            assertEquals(SpectrumFft.BINS, power.size)
            assertEquals(bin, power.indices.maxBy { power[it] })
            assertEquals(-6.0206f, power[bin], .01f)
            assertEquals(bin * 12000.0 / 4096, SpectrumFft.frequency(bin).toDouble(), .0001)
            assertTrue(power[(bin + 8).coerceAtMost(power.lastIndex)] < -75)
        }
    }
    @Test fun silenceDcAndIndependentTwoToneLevelsStayFinite() {
        val fft = SpectrumFft()
        assertTrue(fft.analyze(FloatArray(SpectrumFft.SIZE)).all { it == -100f })
        assertEquals(-12.0412f, fft.analyze(FloatArray(SpectrumFft.SIZE) { .25f })[0], .01f)
        val a = tone(205, .25f); val b = tone(614, .125f)
        val both = fft.analyze(FloatArray(a.size) { a[it] + b[it] })
        assertEquals(-12.0412f, both[205], .02f); assertEquals(-18.0618f, both[614], .02f)
        assertTrue(both.all { it.isFinite() && it in -100f..0f })
        assertThrows(IllegalArgumentException::class.java) { fft.analyze(FloatArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { fft.analyze(FloatArray(4096) { Float.NaN }) }
    }
    @Test fun fftMatchesDirectDftForANonPeriodicInput() {
        val pcm = FloatArray(4096) { ((it * 37 % 101) / 101f - .5f) * .4f }
        val actual = SpectrumFft().analyze(pcm)
        val weights = DoubleArray(4096) { .5 - .5 * cos(2 * PI * it / 4095) }
        for (bin in listOf(0, 1, 89, 237, 649, 1024)) {
            var real = 0.0; var imag = 0.0
            for (i in pcm.indices) {
                val value = pcm[i] * weights[i]; val angle = 2 * PI * bin * i / 4096
                real += value * cos(angle); imag -= value * sin(angle)
            }
            val amplitude = hypot(real, imag) * (if (bin == 0) 1 else 2) / weights.sum()
            val expected = (20 * log10(maxOf(amplitude, 1e-5))).toFloat().coerceIn(-100f, 0f)
            assertEquals(expected, actual[bin], .001f)
        }
    }
    @Test fun streamingWindowsPreserveSamplesAndDropPendingWorkWithoutBlocking() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val windows = mutableListOf<FloatArray>()
        val monitor = SpectrumMonitor(backgroundScope, dispatcher) { windows += it; List(1025) { -50f } }
        monitor.offer(FloatArray(4095) { it.toFloat() }); runCurrent(); assertEquals(0, windows.size)
        monitor.offer(floatArrayOf(4095f)); runCurrent(); assertEquals(1, windows.size)
        repeat(4) { index -> monitor.offer(FloatArray(6000) { (4096 + index * 6000 + it).toFloat() }) }
        runCurrent()
        assertEquals(3, windows.size); assertEquals(2, monitor.uiState.value.replaced)
        assertArrayEquals(FloatArray(4096) { it.toFloat() }, windows.first(), 0f)
        assertArrayEquals(FloatArray(4096) { (24000 + it).toFloat() }, windows.last(), 0f)
        assertEquals(28096, monitor.uiState.value.frames.first().endSample)
        monitor.cancel()
    }
    @Test fun historyIsBoundedAndCancelledWorkersCannotPublishLateFrames() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = SpectrumMonitor(backgroundScope, dispatcher) { List(1025) { -50f } }
        monitor.offer(FloatArray(4096)); runCurrent()
        repeat(110) { monitor.offer(FloatArray(6000)); runCurrent() }
        assertEquals(96, monitor.uiState.value.frames.size); assertEquals(111, monitor.uiState.value.processed)
        monitor.cancel(); val old = monitor.uiState.value
        monitor.offer(FloatArray(12000)); runCurrent(); assertSame(old, monitor.uiState.value)
        lateinit var blocked: SpectrumMonitor
        blocked = SpectrumMonitor(backgroundScope, dispatcher) { blocked.cancel(); List(1025) { -50f } }
        blocked.offer(FloatArray(4096)); runCurrent(); assertTrue(blocked.uiState.value.frames.isEmpty())
    }
    @Test fun failedWindowsDoNotStopTheNextWindow() = runTest {
        var fail = true
        val monitor = SpectrumMonitor(backgroundScope, StandardTestDispatcher(testScheduler)) {
            if (fail) error("Test failure") else List(1025) { -50f }
        }
        monitor.offer(FloatArray(4096)); runCurrent(); assertNotNull(monitor.uiState.value.error)
        fail = false; monitor.offer(FloatArray(6000)); runCurrent()
        assertEquals(1, monitor.uiState.value.processed); assertNull(monitor.uiState.value.error); monitor.cancel()
    }
    @Test fun zoomPanAndCursorSelectionUseTheSameFrequencyMapping() {
        var view = SpectrumView().select(1123.4f)
        assertEquals(1123, view.rxHz); assertEquals(1500, view.txHz)
        view = view.zoom(750f); assertEquals(1123f, view.at(.5f), .001f)
        for (fraction in listOf(0f, .25f, .5f, 1f)) assertEquals(fraction, view.fraction(view.at(fraction)), .0001f)
        repeat(10) { view = view.pan(-1) }; assertEquals(0f, view.startHz, 0f)
        repeat(10) { view = view.pan(1) }; assertEquals(2250f, view.startHz, 0f)
        view = view.copy(cursor = OffsetCursor.TX).select(4000f)
        assertEquals(2950, view.txHz); assertEquals(1123, view.rxHz)
        assertEquals(200, view.select(-20f).txHz)
        assertEquals(0f, view.zoom(3000f).startHz, 0f)
        assertThrows(IllegalArgumentException::class.java) { view.select(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SpectrumView(startHz = 100f) }
    }
    @Test fun decodedMarkersUseOnlyTheLatestLocalSlotAndValidFrequencies() {
        val payload = net.vash.ft8cbdroid.core.Ft8Payload77.fromBits("0".repeat(77))
        fun row(utc: Long, hz: Float): LiveDecodeRow {
            val time = java.time.Instant.ofEpochMilli(utc)
            val frame = net.vash.ft8cbdroid.core.Ft8DecodedFrame(payload, .8f, hz, 30)
            val decode = net.vash.ft8cbdroid.core.recording.RecordedDecode(frame,
                net.vash.ft8cbdroid.core.message.UnpackResult.Unsupported("Test"), time, time, .3f, emptySet())
            return LiveDecodeRow(decode, "Test time", null)
        }
        val markers = localSpectrumMarkers(listOf(row(15000, 1000f), row(30000, 1700f), row(30000, 1700f), row(30000, Float.NaN), row(30000, 4000f)))
        assertEquals(listOf(SpectrumMarker(1700f, 30000)), markers)
        assertTrue(localSpectrumMarkers(emptyList()).isEmpty())
    }

}

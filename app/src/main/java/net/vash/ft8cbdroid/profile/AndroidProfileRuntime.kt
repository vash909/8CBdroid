package net.vash.ft8cbdroid.profile

import kotlinx.coroutines.withTimeout
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.lan.LanViewModel
import net.vash.ft8cbdroid.time.TimeViewModel
import net.vash.ft8cbdroid.core.profile.OperatingProfile

/** Holds backend owners, never an Activity or Compose state. No radio open/PTT path exists yet. */
class AndroidProfileRuntime(private val capture: CaptureViewModel, private val lan: LanViewModel,
    private val time: TimeViewModel, private val waveform: net.vash.ft8cbdroid.message.WaveformViewModel) : ProfileRuntime {
    override suspend fun stop() {
        withTimeout(15_000) {
            waveform.stopForProfile()
            capture.stopForProfile()
            lan.stopForProfile()
            time.resetForProfile()
        }
    }
    override suspend fun configure(profile: OperatingProfile) {
        capture.configureProfile(profile)
        lan.configureProfile(profile.lan)
    }
}

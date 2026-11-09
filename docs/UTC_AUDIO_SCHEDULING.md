# One-shot UTC audio scheduling

**Settings → Message tools** can queue one generated FT8 slot through the phone
speaker. This remains a foreground bench tool. CAT/PTT, radio VOX, automatic
repetition and live Enable TX on Operate are unavailable.

1. Synchronize and explicitly apply an NTP or GPS measurement in **Time synchronization**.
2. Generate a message and explicitly select **Phone speaker** in Message tools.
3. Press **Measure speaker latency**. A one-second silent AudioTrack measures
   advancing presentation timestamps; three consistent observations are required.
4. Select **Even · 00/30** or **Odd · 15/45**, then **Queue one UTC slot**.
5. Keep the page visible. Stop, edits, output/period changes, navigation,
   background/rotation and profile changes cancel the current operation.

The next matching slot must leave at least two seconds for preparation. A queued
operation freezes its message PCM, level/ramps, output, measured latency and UTC
mapping. Clock corrections never move it to another slot. Completion leaves no
next slot armed. PCM, calibration, output ID and pending work are never persisted.

## Timing and failure handling

The pinned desktop [FT8 modulator](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Modulator/Modulator.cpp#L75)
starts its first tone 500 ms into the period; provenance and timing values are
in [the timing fixture](../reference/wsjtcb/tx-timing.json). R07's unchanged
15-second WAV contains 800 ms of leading silence. Scheduled playback starts
that PCM 300 ms before the UTC boundary so its first tone targets UTC +500 ms.
Immediate preview and exported WAVs retain their original timing.

The backend prefills 100 ms of silence for both calibration and playback.
Measured presentation latency is subtracted from the planned PCM start. The
core planner uses the NTP/GPS mapping and monotonic clock; there is no manual
latency/UTC override or fallback to unsynchronized system time.

Calibration is process-local, valid for five minutes and tied to the selected
output inventory. Unavailable, negative, over-500 ms or unstable (over-20 ms
spread) estimates fail explicitly. Clear/reselect the output or applying a
profile requires a fresh measurement. There is no silent route substitution.

A command more than 20 ms late is rejected. During waiting and playback, the
backend checks correction revision/expiry, wall-clock jumps, monotonic clock
continuity, focus and route. A correction must remain valid through the whole
slot. Before the active signal, advancing output timestamps must confirm the
planned first frame within 75 ms; otherwise playback is aborted. There is no
catch-up, truncated late message or automatic retry. Cancellation joins cleanup
before another output session is admitted.

Android [AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp#nanoTime)
is an estimated presentation time in the `System.nanoTime()` clock domain.
The backend bridges this to the boot clock and rejects suspend-time discontinuity.
This is not a guarantee of DAC or acoustic onset accuracy: NTP/GPS uncertainty,
Android routing/processing and physical hardware remain relevant. GNSS timing
uncertainty stays unknown when Android does not provide it. USB, wired and
Bluetooth outputs are outside this feature.

## Validation

Virtual-clock tests cover parity, near boundaries/day rollover, measured
compensation, stale calibration, missed deadlines, clock revision/reset/jumps,
expiry and timestamp stability. ViewModel tests cover frozen snapshots, duplicate
queue attempts, cancellation, explicit retry and process-local state.

With matching app/test/probe APKs, a synchronized Linux PC, a physical default
microphone near the unlocked phone and `libpulse-simple`/PipeWire tools:

```bash
python3 scripts/scheduled_audio_phone.py --output /path/to/new-private-directory
```

The test uses a real NTP query without editing saved time settings. It records
even/odd slots and exercises the actual Android late-deadline rejection, clock
correction changes, edits, Stop, focus loss, navigation, recreation and profiles.
A unique run ID prevents stale reports/acknowledgements from passing.

`scripts/audio_timing_capture.py` records 48 kHz mono PCM16 and per-chunk UTC
end-frame estimates using the host clock minus PulseAudio's reported input
latency. See [PulseAudio timing semantics](https://freedesktop.org/software/pulseaudio/doxygen/def_8h.html).
Use those timestamps to measure acoustic onset against slot +500 ms and decode
the captured signal independently. Query duration, server latency and host/NTP
quality limit the measurement; file creation time is not a capture timestamp.
Recordings can contain ambient audio and must remain private. Written frame
counts or a successful deployment alone do not qualify physical timing.

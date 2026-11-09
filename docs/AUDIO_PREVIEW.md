# Generated audio preview

In **Settings → Message tools**, generate a WAV as before, explicitly select
**Phone speaker**, and press **Play speaker preview**. One complete 15-second
slot plays immediately. The separate [one-shot UTC controls](UTC_AUDIO_SCHEDULING.md)
add scheduled bench playback; CAT/PTT and radio VOX remain unavailable.
Keep radios disconnected for this bench feature. Operate's Enable TX stays disabled.

Microphone reception on the same page is coordinated with output. Optional
**Resume microphone after successful output** applies only to an already active
built-in input; errors and cancellation never resume it. See
[phone audio coordination](PHONE_AUDIO.md) for controls and recovery limits.

## Controls and lifetime

- The generator's peak level remains part of its waveform. **Preview gain**
  (1–100%) multiplies that level without amplifying beyond it; Android media
  volume also controls audible loudness. For example, 40% generated peak and
  50% preview gain produce a 20% digital peak before Android mixing/volume.
- **Preview edge ramp** (5–100 ms, default 20 ms) adds raised-cosine fades at
  the active signal's edges. The existing generated waveform already has its
  own 20 ms shaping. Neither the 0.8-second lead silence nor the slot length
  changes. These preview settings do not modify the exported WAV.
- **Stop preview** fades briefly, discards queued output and releases AudioTrack
  and focus. Another play is unavailable until cleanup finishes.
- Editing/regenerating a message, changing output/settings, applying a profile,
  leaving Message tools, rotation or backgrounding stops preview. There is no
  automatic restart or resume on audio-focus gain.
- Clearing output selection stops playback without choosing a replacement.
  Process restart restores neither PCM nor the selected Android device ID.
  A saved profile's output preference does not bypass explicit preview selection.

Only the built-in phone speaker is currently offered. USB/wired/Bluetooth output
and radio VOX operation require separate integration and physical qualification.
Changes in Android's output inventory stop playback; actual routing is checked,
and a route that differs from the selected speaker aborts without fallback.

## Backend and boundaries

The core `AudioPreview` contract owns playback until cleanup finishes. Its PCM
preparation is pure JVM code; Android focus, devices and AudioTrack live in
`audio`. The ViewModel admits one immutable generated slot at a time and waits
for microphone/listener cleanup with a 15-second deadline before opening the speaker. No I/O runs in Compose.

The common output path prefills 100 ms of silence before Play. AudioTrack uses mono float PCM at a **12,000 Hz client rate**, nonblocking bounded
writes, confirmed speaker routing and a finite playback/drain deadline. Reports
show client rate and written/played frames. Android may resample in its output
mixer; the client rate does not establish the hardware DAC rate. Focus denial,
loss/duck requests, output changes, route loss and write failures stop the slot
and require an explicit retry. Partial or interrupted previews are not sent QSOs.

The implementation follows Android's [audio-focus lifecycle](https://developer.android.com/media/optimize/audio-focus)
and checks the actual route because [preferred AudioTrack devices](https://developer.android.com/reference/android/media/AudioTrack#setPreferredDevice(android.media.AudioDeviceInfo))
are routing preferences rather than proof of the device in use.

## Verify

JVM checks cover gain/ramp bounds, exact slot length/silence, immutable PCM,
explicit starts, cleanup ordering, late updates, preparation cancellation,
output removal, edits, errors and retry. The normal check builds phone fixtures.

With matching CI app/test/probe APKs and PipeWire tools installed, run:

```bash
python3 scripts/audio_preview_phone.py --output /path/to/new-private-capture-directory
```

Keep the unlocked phone speaker near the PC microphone. This runs the `preview` scenario of
`net.vash.ft8cbdroid.validation.WaveformPhoneChecks`. It waits for a PC recorder
acknowledgement at two full-slot stages (`gain-100`, `gain-50`) through the app's
private cache, with a unique run identifier to reject stale acknowledgements. Recordings may contain ambient audio; keep them private. It exercises real speaker output, UI Play/Stop/Clear, message
invalidation, separate-app focus loss, navigation, background, rotation and
profile cleanup. The separate probe's translucent focus fixture requests focus
without producing audio and exits after 1.5 seconds.

Capture the actual phone speaker with a physical PC microphone. Verify signal
frequency/duration, relative levels, absence of clipping and independent desktop
FT8 decoding. Written/played frame counts alone cannot establish audible output.
No emulator, offline DSP probe or audio-file export substitutes for that check.
Record exact tested head, CI run and device results in the PR/validation evidence.

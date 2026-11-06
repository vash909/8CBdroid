# Microphone capture (R10)

The **Audio → Microphone input** panel captures mono PCM16 through Android
`AudioRecord`, meters the input and converts it to 12 kHz floating-point PCM.
It does not assemble UTC slots, call the FT8 decoder, play audio or operate PTT.
R11–R12 connect timestamped capture to live reception; R14 and R17 cover output
and acoustic VOX. A microphone level meter is not yet a local FT8 receiver.

## Start, stop and permission

1. Select a listed input. Built-in microphones, wired headset microphones,
   analog input and Android USB audio devices can be enumerated. Bluetooth is
   not supported by this backend.
2. Press **Start microphone** and allow Android's microphone permission. Denial
   opens no recorder. Retry explicitly; after permanent denial, allow Microphone
   in Android app settings first.
3. Confirm **Microphone active**, the **Actual input**, source, formats and
   levels. Selection is a preference until Android confirms the routed device.
   A different route is an error, rather than silently accepting another input.
4. Press **Stop microphone**. The last five seconds remain available for
   **Save recent microphone WAV**. The export is mono PCM16 at 12,000 Hz, at
   most five seconds, with no UTC or FT8-slot claim. It cannot be imported by
   the separate recorded-slot decoder, which requires a complete 15-second slot.

Capture stops and clears its PCM when leaving Audio, rotating or backgrounding.
A permission result arriving after Stop cannot restart it. Stop waits for worker
cleanup before another start is allowed. Startup, interruption, permission loss,
Android silencing, route loss/change and a two-second no-data stall require an
explicit retry. No service or automatic reconnect/rearm is included.

Only explicit WAV export writes microphone audio to a document. Other samples
remain in memory, are overwritten after five seconds and are cleared on a new
start, input change, capture failure or foreground-session end. Export destinations
are user-owned documents; the app does not delete them. Station preferences are
unaffected. The permission and Android privacy indicator belong to the OS.

## Format, processing and work bounds

- Open 48,000 Hz mono PCM16, falling back to 44,100 Hz if initialization fails.
  Other client formats/rates are rejected. Android can perform additional
  device-side conversion; the displayed device format comes from its recording
  configuration and does not certify the physical ADC rate.
- Request `UNPROCESSED` only when Android advertises support, otherwise
  `VOICE_RECOGNITION`. Attempt to disable session AGC, noise suppression and echo
  cancellation; display unavailable/unknown/enabled/disabled state and reported
  device effects. Vendor DSP may remain, including when no effect is reported.
  There is no assertion of raw, bit-perfect radio audio.
- Read nonblocking chunks of at most 20 ms, with a 5 ms polling delay. Request a
  recorder buffer of at least 200 ms or twice Android's minimum; reject a
  constructed buffer above one second. No unbounded audio queue exists.
- Use a causal 129-tap Hamming-windowed sinc low-pass at 4.5 kHz, then fractional
  interpolation to 12 kHz. History and rational phase survive read boundaries.
  Filter delay is 64 input samples (about 1.33/1.45 ms); startup is zero-filled,
  no tail is flushed and no UTC timestamp is assigned. R11 must account for this
  delay and detect missing input; R10 counters do not prove lossless capture.
- Retain at most 60,000 floating-point output samples (240,000 bytes), replacing
  the oldest deliberately. Input/DSP sample counts are cumulative for the run.
  UI metrics are updated roughly every 100 ms, independent of the decoder.
- Peak/RMS are measured before resampling in dBFS, floored at -120 dBFS. Clipping
  counts exact PCM16 rails (-32768/+32767), not a calibrated analog overload.
  WAV export saturates filtered values to PCM16; it does not normalize or AGC.

Android's recording-configuration silenced flag is checked on API 29+. On API
26–28 the backend cannot distinguish framework-supplied silence from a quiet
input. Missing configuration/effect information is shown as unavailable/unknown.
A disconnected selected device or changed actual route fails the session; it
never falls back to another input while running.

## Verification

JVM tests cover both input rates, chunk/phase continuity, 30-second sample counts,
200/1000/2900 Hz passband, rejection of 10 kHz aliasing, level/clipping measurements,
ring-buffer overwrite/clear and WAV headers/saturation. ViewModel tests cover
permission denial/retry, late grants, immediate Stop, cleanup before restart,
late updates, simulated route failure, missing-device selection and export
snapshots. These tests do not qualify physical USB detach or vendor audio DSP.

After installing verified matching CI app/probe APKs, unlock the phone and keep
it awake. The separate **AudioCaptureProbe** opens a visible test activity,
records the built-in microphone and plays a short 1000 Hz tone through the
built-in speaker. Keep media volume above zero. It preserves the existing media
volume and never transmits RF. The ordinary **OfflineEngineProbe** still opens
no audio devices; only the explicit audio entry point uses the declared permission.

```bash
source scripts/env.sh
ADB_LIBUSB=1 bash scripts/controlled.sh 90 bash scripts/capture_phone.sh
```

The helper grants microphone permission only to the installed development probe,
uses a 60-second instrumentation deadline and force-stops only the probe on exit.
It checks a received peak within 997–1003 Hz, dominance over 900/1100 Hz controls,
actual input/output routes, PCM bounds, cancellation cleanup and a fresh capture.
The private phone WAV remains in probe storage; it is not uploaded or committed.
The local report contains numeric/route diagnostics, not the recording. A quiet
or obstructed speaker/mic can fail this acoustic check; report the failure rather
than substituting an internally generated buffer for physical capture.

Also test the main UI: deny/allow permission, select/start, observe levels, stop
and save, repeat start/stop, navigate away, rotate and background/reopen. Confirm
idle state with no automatic restart and persistent station settings. Test actual
wired/USB removal when that hardware is available. The built-in A51 path does not
qualify external inputs, radio coupling, drift, sustained operation or live FT8.
Device/CI results are recorded in the feature PR before owner merge approval.

## Android references

- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord):
  nonblocking reads, buffer sizes and preferred versus routed devices.
- [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input):
  foreground/concurrent capture policy and silenced clients.
- [Recording configuration](https://developer.android.com/reference/android/media/AudioRecordingConfiguration):
  reported format, source, effects and API-29 silencing information.
- [Audio sources](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource):
  unprocessed and voice-recognition source behavior.

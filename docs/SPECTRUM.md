# Local spectrum and waterfall

The Audio screen displays the selected microphone's resampled 12 kHz PCM.
It works with plain capture; time synchronization is only required when UTC
assembly or live FT8 decoding is enabled. It never displays LAN or imported WAV
samples as local microphone audio. Phone evidence is in [validation](VALIDATION.md).

## Controls

1. Select the microphone and press **Start microphone**, granting permission.
   Scroll to **Spectrum and waterfall** below the capture controls.
2. **Select RX** or **Select TX**, then tap either part of the plot, move the
   accessible offset slider, or use the 25 Hz buttons. RX and TX are independent
   cursors, limited to 200–2950 Hz. Green is RX; pink is TX.
3. Select **Zoom 1×**, **2×** or **4×** for a 3000, 1500 or 750 Hz view,
   centered on the selected cursor and clamped to the 0–3000 Hz band. Use
   **Lower band** / **Higher band** to pan by half the visible width.
   The three axis labels and tap selection use the same frequency mapping.
4. Yellow dots identify frequencies in the latest local slot containing decoded
   frames. Its UTC is shown explicitly. These are frequency indicators, not
   event timestamps on the waterfall. Press a **Decode … Hz → RX** button to
   select that frequency. Unsupported/unresolved CB text can still have a valid
   channel frequency; selecting it does not establish a peer or a QSO.
5. Stop clears spectrum, waterfall and decoded markers. Rotation, background,
   leaving Audio, route/station changes and capture failure also discard history.
   Restart remains explicit. Cursor/zoom choices survive Stop and rotation in
   the current ViewModel, but are not saved across process recreation.

**The cursors are display selections only.** They do not narrow the FT8 search,
change a radio frequency, connect to the offline waveform editor, start audio
playback or arm transmission. Those actions belong to later milestones.

## Frequency, level and work limits

- The analyzer uses an independently implemented 4096-point radix-2 FFT with a
  Hann window: 341.3 ms of audio, 2.9296875 Hz per bin, displaying 0–3000 Hz.
- A full window is required. Subsequent windows are 6000 samples apart, giving
  at most two updates per second of captured audio. No FFT runs in Compose or
  on the recorder thread. The recorder only copies windows; one worker consumes
  them with one replaceable pending window. Replacements are counted visibly.
- Spectrum values are one-sided peak amplitudes corrected for Hann coherent
  gain, bounded to −100…0 dBFS. A bin-centered sine of peak 0.5 reads about
  −6.02 dBFS. Off-bin tones have window/scalloping loss; microphone hardware,
  resampling and vendor processing affect actual measurements. This is neither
  calibrated RF power nor an SNR estimate.
- The waterfall color range is −90…−10 dBFS. Its newest row is at the top;
  black/blue is quiet and brighter colors are stronger. At most 96 rows and
  1025 bins per row are retained. At full rate without replacements, this spans
  about 48 seconds; replaced display windows can extend that interval. Rows
  are not a UTC grid and are not padded to hide missing display updates.
- Color/bitmap preparation runs on a display worker. Compose draws the latest
  matching image and spectrum path; cancelled/old history cannot be republished
  after Stop. Fast meter and spectrum observations have separate UI collectors and do not
  invalidate the app shell. Detailed format/effect/sample counters are available
  under **Show audio diagnostics**. The FT8 decoder retains its own work gate and slot queue.

## Reproduce

JVM checks compare FFT results with direct DFT sums and independent tones,
including amplitude, silence, DC and a two-tone signal. They also check ring
window ordering, bounded pending work/history, failure recovery, cancellation,
frequency mapping and capture reset without requiring Android/NTP.

Deploy matching successful CI app/test APKs, unlock the phone near the PC
speakers and grant microphone permission. Linux needs `pw-play` and a working
PipeWire session. This finite check generates independent 700/1900 Hz tones,
plays them through the PC speakers and keeps the actual plot visible for two
minutes. No samples or analyzer results are injected into the phone.

```bash
source scripts/env.sh
timeout 240 bash scripts/check_spectrum_phone.sh
```

The probe checks frequency error within 6 Hz, stable tone reception, at least
220 analyzer updates over 120 seconds, the 96-row limit, actual UI render counts,
post-warmup memory growth and Stop cleanup. It records measured PSS, process CPU,
observed analysis times and Android graphics statistics under ignored `.tooling/`.
These short measurements are not a thermal/battery or long-session qualification.
Memory is sampled after 60 seconds, when the history is full, and again at the
end. Graphics statistics are diagnostic: a 2 Hz instrument does not require a
60 fps animation target. Acceptance requires sustained updates and actual
cursor/zoom response within one second. Fast meter observations are sampled
for the UI twice per second; audio capture, route checks and FT8 timing retain
their original cadence.

Global audio volume stays unchanged; temporary charging stay-awake is restored.

Also run `scripts/check_live_phone.sh` under the documented controlled limit to
verify actual FT8 reception alongside visualization and decoded-marker selection.
Exercise real zoom/pan, tap/slider/buttons, permission denial/retry and lifecycle
controls on the matching CI APK. Record the tested revision and actual results.

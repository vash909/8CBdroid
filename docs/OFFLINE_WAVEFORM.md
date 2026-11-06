# Offline CB waveform generation (R07)

The **Messages** tab generates a WAV from a supported CB FT8 message, using the
R05 codec and the source-built native channel engine. This is file generation
only: this feature does not play audio, operate PTT or schedule TX. The separate
[R10 microphone panel](MICROPHONE_CAPTURE.md) captures diagnostic audio; it does
not provide live FT8 decoding.

## Behavior and format

1. Enter the message and inspect its existing encoded-text preview.
2. Set **Base audio frequency (Hz)**, 200–2900 Hz, and **Peak level (%)**, 1–100.
   Frequency refers to the first tone; the other tones are spaced by 6.25 Hz.
   Level is digital peak amplitude, not RF power or hardware volume calibration.
3. When packing changes/truncates content, explicitly accept the encoded message.
   Unsupported input cannot generate a waveform. Consent resets on input/settings
   changes; an unresolved `<...>` is never transmitted as an invented identity.
4. Tap **Generate offline WAV**, then **Save WAV** and choose a document location.
   Generation never starts automatically. Cancelling the document picker keeps
   the generated result available. A failed save can be retried.

`OfflineFt8Generator` is pure Kotlin orchestration behind `Ft8Engine`. It passes
the exact 77-bit payload, base frequency and linear amplitude to the native
encoder. `Ft8GenerationSettings` validates finite parameters. The output is mono
PCM at exactly **12,000 Hz**, **180,000 samples / 15 seconds**, containing 79
symbols at 1,920 samples each (**12.64 seconds**), starting at 0.8 seconds. The
existing raised-cosine start/end ramps last 240 samples (**20 ms**) each.

WAV export is canonical 44-byte RIFF/WAVE, signed little-endian PCM16 with a
32767 scale and nearest-integer rounding. Total file size is **360,044 bytes**.
Android document access stays in `DocumentWaveformExporter` on an IO dispatcher;
the pure writer has JVM tests. No storage permission or account is required.

`WaveformViewModel` owns one generation, cancellation and immutable export bytes.
Editing input/settings invalidates an old result; late native results cannot
replace a newer request. Native cancellation is checked between waveform symbol/chunk operations;
cleanup precedes admission of the next job. The engine gate is shared with
recorded decoding, so a full queue reports a busy/retry error. See
[work bounds](DECODER_PROFILES.md). Saving uses the snapshot selected when **Save WAV**
was tapped, even if inputs later change. Rotation retains the ViewModel; process
termination discards generated PCM and unsaved parameters. Exported documents
and saved station settings remain separate. RX hash learning is unaffected.

## Independent checks and limits

`scripts/wsjtcb_pcm.py` qualifies **21 clean waveforms**: seven pinned message
payloads at `(200 Hz, 1%)`, `(1000 Hz, 40%)` and `(2900 Hz, 100%)`. Cases include
CB CQ, both type 4 orientations, a destination-only report, standard calls and
free text. Every output must match the independently generated 79-tone vector
and recover the exact payload and cold text through original WSJT-CB routines.

Four unchanged host-only sources are pinned in `reference/wsjtcb/pcm-sources.json`
at `94f776d54946fe43db5e0bb9c966b1595c7b7d97`: `bpdecode174_91var`, its parity
table, `chkcrc14a` and `platanh`. They use the existing pinned CRC implementation
and original `unpack77var`. A small test adapter performs Goertzel demodulation
with **supplied frequency and the generated 0.8-second onset**, checks all Costas symbols,
then invokes the desktop LDPC/CRC decoder and text unpacker. No decoder algorithm
is stubbed or replaced with ft8_lib's own decoder in this independent check.

The checker also measures PCM bounds, peak/RMS, slot/signal duration, silence and
both ramp envelopes. Two out-of-band frequency probes per symbol must stay below
2% of the strongest tone's energy. These are limited spectral probes, not a
complete spectral mask. Invalid parameters, silence and a wrong-frequency decode
must fail. Native encode/decode round trips supplement the independent check.

This is a clean-channel qualification with supplied synchronization, **not** the
full desktop candidate search, weak-signal demodulator, acceptance filters or
RF interoperability. Actual phone exports have also passed the separate full
WSJT-CB GUI file-import check described in [validation](VALIDATION.md#r07-closure-checks-pr-26).
No noisy sensitivity or desktop decoder parity is claimed. [Recorded reception](RECORDED_SLOTS.md) now covers a small fixed noisy corpus;
broader decoder cost/quality qualification is still pending.

```bash
source scripts/env.sh
bash scripts/controlled.sh 600 bash scripts/check.sh
# Host tools: gfortran, C++17/Boost, CMake and Ninja, as in FT8_ENGINE.md.
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_pcm.py .tooling/native-host
```

CI runs the independent channel checks and builds the app, engine probe and
app device-check APKs. The phone probe
additionally generates 12 message waveforms across the three frequency/level
pairs and saves `files/r07-generated.wav` in its private test package. That file
can be copied via `adb exec-out run-as` and checked with `wsjtcb_pcm.py --wave`,
supplying its expected payload, frequency and level. [Validation](VALIDATION.md#r07-export-evidence)
records the tested R07 commit/CI run, A51 checks and exact exported-file hash.
Generation/export, invalid settings, consent/reset, invalidation on edits and
reopening passed. R07 closure adds generation/export rotation, picker cancellation,
actual provider failure/retry, queued UI and active native cancellation, unresolved
input rejection and full desktop GUI decoding of CQ/both hash orientations.
[scripts/check_waveform_phone.sh](../scripts/check_waveform_phone.sh) reproduces
the test-only instrumentation subset after verified app/test APK deployment;
[development](DEVELOPMENT.md#r07-device-checks) explains its scope.
R08 recorded-file tests remain separate evidence.

<a id="deferred-phone-and-desktop-checklist"></a>

## Phone and desktop checklist

This is a reusable checklist, not a claim that every step passed. Check the
[validation record](VALIDATION.md#checks-still-pending) for outstanding items.

1. Let the agent deploy the successful PR CI APK via ADB, preserving station data.
2. Generate `CQ 1AT106` at 1000 Hz / 40%, save it and verify the metadata/size.
   Load it in WSJT-CB's FT8 desktop decoder; require `CQ 1AT106`.
3. Repeat both directed hash orientations. A cold receiver may display `<...>`;
   compare the payload and use the R06 hash rules rather than inventing a peer.
4. Enter `<1AT106> 26AT101 R-10`. Generation requires confirmation and the file
   carries `<1AT106> 26AT101`, with the report loss visible in the preview.
5. Try invalid frequency/level and `<...>` input. Verify explicit rejection.
6. Change inputs after generation, cancel generation/picker, retry a failed save,
   rotate and reopen the app. Verify stale output cannot be saved as a new request,
   station settings persist, and all controls remain English.

# Offline FT8 channel engine

The source-built native backend encodes raw FT8 payloads and decodes complete
PCM slots. The main app uses it for [offline WAV generation](OFFLINE_WAVEFORM.md)
and [recorded-slot import](RECORDED_SLOTS.md). These paths do not open live audio,
control a radio or implement AutoSeq. Current test/device outcomes are in
[validation](VALIDATION.md).

## Decision and dependencies

The channel backend introduced in R04 uses
[ft8_lib](https://github.com/kgoba/ft8_lib/tree/9fec6ca39886edbf96f4f5e71edc76da5074e871)
commit **`9fec6ca39886edbf96f4f5e71edc76da5074e871`** (MIT), with bundled KISS FFT
(BSD-3-Clause). Exact paths/checksums are in the
[vendored manifest](../native/third_party/ft8_lib/manifest.json). The waveform
adapter follows the upstream GFSK demo. No native binary is downloaded.

Build settings are NDK **28.2.13676358**, CMake **3.22.1**, C99/C++17, minimum API
26, and **arm64-v8a/x86_64**. ARM32 is absent. LLVM libc++ is statically linked;
the FT8 DSP library links no Hamlib, Qt, Fortran or Boost runtime. The app
packages its separate [Hamlib catalog library](HAMLIB.md) for radio metadata
and software-only Dummy checks.
[Third-party notices](../THIRD_PARTY_NOTICES.md) describe the packaged licenses.

The inspected FT8CN tree at `a98fbfaec379a0c7a07d2a50c27be223aef948d8` served as
an operating-feature reference; it supplied prebuilt DSP libraries without
native sources in that tree. The chosen backend can be built from source and
keeps CB policy separate from channel coding.

The unmodified ft8_lib amateur text codec is incompatible with the CB corpus:
R04 observed 63 mismatches in the original 66 cases; the expanded 82-case corpus
reports 68. `codec_probe` lists the current cases. Its `message.c`/`text.c` remain
host-only comparison inputs and are **excluded from Android**. Runtime text
packing/unpacking comes from the [Kotlin CB codec](CB_MESSAGE_CODEC.md), with
[separate hash sessions](CB_HASH_SESSIONS.md).

## Contracts and execution

| Contract | Representation |
| --- | --- |
| `Ft8Payload77` | Ten bytes, with three zero padding bits; defensive copies. |
| `Ft8PcmSlot` | Exactly 180,000 finite normalized float samples in [-1, 1]: mono 12 kHz, 15 seconds. |
| `Ft8ReceiveWindow` | Exactly 162,000 genuinely captured normalized samples for early foreground AutoSeq decisions. The native decoder processes 13.5 seconds directly, without adding unrecorded samples. |
| `Ft8DecodedFrame` | CRC/LDPC-valid payload, corrected relative onset, audio frequency, sync score and nullable estimated SNR. |
| `Ft8DecodeResult` | Decoded frames plus candidate count; duplicate payloads collapsed within a slot. |

`NativeFt8Engine` runs JNI on `Dispatchers.Default`. A process-wide mutex allows
one encode/decode call at a time across instances, with at most one FIFO waiter.
Further requests fail with a busy/retry error before JNI allocation. Buffers and native work belong
to each call; text, hash tables, absolute UTC and QSO state stay outside native.
Malformed payload padding, incomplete slots and non-finite/out-of-range PCM are
rejected before use.

The encoder produces a fixed slot with signal onset at **0.8 s**, 79 symbols
at 1,920 samples each (**12.64 s**) and **20 ms** start/end ramps. Base audio
frequency is **200–2900 Hz** and peak amplitude **0.01–1**, default **0.4**.
These are digital waveform parameters, not hardware volume, UTC scheduling or
RF power calibration. The message layer checks unsupported/lossy content before
encoding; see [WAV behavior and format](OFFLINE_WAVEFORM.md).

## Decoder metadata and limits

The search uses **200–3000 Hz**, **2x time/frequency oversampling**, at most
**60 candidates** and **25 LDPC iterations** per candidate in Standard.
Fast uses 24 candidates and 12 iterations; both retain the same integrity checks.
Their cooperative native deadlines are 4 seconds and 2 seconds respectively.
See [profiles and admission policy](DECODER_PROFILES.md). Frames require zero
parity errors and matching CRC. Deduplication by payload can collapse identical
messages at different offsets; no complete multiple-signal behavior is claimed.

R08 corrects the fixed one-symbol (**160 ms**) FFT-window delay in reported
onset. Search timing has an **80 ms** grid. The recorded-slot JVM layer adds the
operator-supplied UTC boundary and derives DT as onset minus **0.5 s**, rounding
absolute signal UTC to milliseconds. Document dates never establish UTC.

**Sync score is not SNR.** R08 separately estimates SNR in a 2500 Hz bandwidth
from recovered-tone power and outside-band noise probes. JNI uses NaN for an
unavailable value; Kotlin exposes `null`. This estimator is experimental and
can be biased by interference, colored noise, overlap or timing/frequency error.
See [the estimator and recorded-slot limits](RECORDED_SLOTS.md#snr-estimate-and-decoder-limits).
It is not WSJT-CB's calibrated estimator.

R09 adds cancellation checks between FFT blocks, candidate operations and SNR
symbols, with atomic per-call flags and cleanup before gate capacity is released.
Individual vendor calls remain non-preemptible. A fixed 40-slot/profile corpus
and a finite device diagnostic measure reception/cost without claiming desktop
parity; wider weak-signal/false-positive qualification and long thermal/battery
operation remain unqualified. The upstream monitor retains its original
C allocation behavior; memory-pressure recovery needs further work. A valid
channel frame alone does not establish supported CB text, a peer or a QSO.

## Independent desktop checks

The pinned WSJT-CB revision is
**`94f776d54946fe43db5e0bb9c966b1595c7b7d97`**. Original host-only Fortran/C++
sources generate or verify expected results; Boost implements the original CRC
wrapper. No Fortran/Qt/Boost runtime is shipped in the APK.

| Check | Independent reference / boundary |
| --- | --- |
| [Channel corpus](../reference/wsjtcb/channel/channel.json) | 131 exact payload/codeword/tone vectors and four clean WAVs generated through original `encode174_91`, `get_ft8_tones_from_77bits`, `gen_ft8wave`; sources pinned by [channel manifest](../reference/wsjtcb/channel-sources.json). |
| [Generated PCM](OFFLINE_WAVEFORM.md#independent-checks-and-limits) | 21 cases checked through original desktop LDPC/CRC/unpacker plus supplied-time/frequency Goertzel analysis, duration/envelope and limited spectral probes; additional sources pinned by [PCM manifest](../reference/wsjtcb/pcm-sources.json). |
| [Recorded corpus](../reference/wsjtcb/recordings/recordings.json) | Four clean, four fixed AWGN, two onset shifts, corrupted data and noise-only rejection. Native search compared with a supplied-sync desktop channel adapter. |

Channel vectors deliberately include payloads whose text can be unsupported or
lossy. The regular native checker verifies original fixture hashes and exact
codewords/tones. Desktop waveform regeneration allows eight PCM16 units for
Fortran/libm rounding, while committed WAV hashes must match exactly. Own-engine
round trips supplement rather than replace independent inputs.

After building `.tooling/native-host` and configuring the host compilers as in
[development](DEVELOPMENT.md#desktop-reference-and-native-checks):

```bash
source scripts/env.sh
timeout 120 python3 scripts/check_native.py .tooling/native-host
timeout 180 python3 scripts/wsjtcb_pcm.py .tooling/native-host
timeout 180 python3 scripts/wsjtcb_recordings.py .tooling/native-host
```

These adapter checks do not run the full WSJT-CB desktop search/plausibility
filters or establish decoder parity. The separate full desktop GUI check passed
actual phone-exported CQ and both directed hash orientations; see
[R07 closure evidence](VALIDATION.md#r07-closure-checks-pr-26).

## Phone probe and evidence

`engineprobe` is a separate development APK. Its ordinary `OfflineEngineProbe`
entry point opens no audio, USB or network resources. R10 adds a separate opt-in
[audio probe](MICROPHONE_CAPTURE.md#verification) with microphone permission.
The offline entry point uses the same core/native backend and packages test data
excluded from the main app. It now checks 154 message cases, 96 hash scenarios,
12 generated waveforms, four original desktop WAVs and 12 recorded-slot cases,
plus roundtrip, silence and invalid-input rejection.

For local-build smoke testing, after the regular build:

```bash
source scripts/env.sh
timeout 120 bash scripts/probe_phone.sh
```

This helper installs the **local** probe APK and force-stops only that package
on exit/timeout. For a code PR, deploy the verified **CI** APKs as described in
[phone deployment](DEVELOPMENT.md#deploy-and-test-on-a-phone). A signing mismatch
is not permission to uninstall or clear data. Inspect both the explicit `PASS`
and `INSTRUMENTATION_CODE: -1`.

The ordinary probe reports per-case time, post-test process PSS and native allocation.
The explicit [R09 profile run](DECODER_PROFILES.md#device-procedure-and-limits)
adds sampled memory maxima, profile latencies and short thermal observations.
These are short offline observations; post-test PSS is not peak memory. CI builds
the probe but does not run a physical device.

The original R04 gate passed on the A51 / Android 13 / ARM64 on 2026-11-05 using
probe SHA-256 `46c3d9603cee4fb4ad1cb9ef6f441912377ee58d279591913954b71b89a426ce`:
four WAV decodes at 151/147/180/172 ms, encode 37 ms, PSS 45,135 KiB and native
allocation 7,705,296 bytes. Those historical observations predate R08 metadata
changes. [Validation](VALIDATION.md#samsung-a51-result-r08) records the
latest merged revision, current probe/UI checks and outstanding qualification.

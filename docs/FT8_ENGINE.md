# Offline FT8 channel engine (R04)

R04 selects a source-built channel backend for offline experiments. It does not
add local RX/TX controls to the app. The separate [R05 text codec](CB_MESSAGE_CODEC.md) adds offline packing/unpacking.
[R06 hash learning](CB_HASH_SESSIONS.md) and [R07 offline WAV generation](OFFLINE_WAVEFORM.md)
are separate layers. AutoSeq, live audio and radio operation remain later milestones.

## Decision and dependencies

Use [ft8_lib](https://github.com/kgoba/ft8_lib/tree/9fec6ca39886edbf96f4f5e71edc76da5074e871)
commit `9fec6ca39886edbf96f4f5e71edc76da5074e871` (MIT), with its bundled KISS FFT
(BSD-3-Clause). Exact vendored paths and SHA-256 values are in
[`manifest.json`](../native/third_party/ft8_lib/manifest.json). No native binary
is downloaded. Build with NDK **28.2.13676358**, CMake **3.22.1**, C99/C++17;
package **arm64-v8a** and **x86_64**, minimum API 26. ARM32 is not built.
The C++ runtime is linked statically. There is no Hamlib, Qt, Fortran or Boost
runtime in the Android library. See [notices](../THIRD_PARTY_NOTICES.md).

The inspected FT8CN tree at `a98fbfaec379a0c7a07d2a50c27be223aef948d8` supplies
prebuilt DSP libraries under `ft8cn/app/libs/`, without their native source in
that tree. It remains a useful operating-feature reference. A source-built
channel layer allows reproducible builds and future CB-specific adaptation.

At R04 selection, the unmodified ft8_lib amateur text codec differed from the
pinned WSJT-CB production packer on **63 of 66** R03b inputs, including rejection and different
payloads. `codec_probe` reports the exact cases; `message.c` and `text.c` are
**excluded from Android**. Do not use their callsign/message policy for CB.
The selected layer accepts raw 77-bit payloads only. R05 implements the separate
text codec and R06 adds learned RX hash behavior before live-operation milestones.

## Contracts and bounded work

`core.Ft8Engine` encodes `Ft8Payload77` to `Ft8PcmSlot` and decodes a complete
slot to typed frames: raw payload, relative start time, audio frequency and sync
score. Buffers own defensive copies; malformed bit lengths/padding, incomplete
slots and non-finite/out-of-range PCM are rejected. DSP PCM is mono float at
12 kHz, normalized to [-1, 1], exactly 180,000 samples (15 seconds).

The encoder exposes a peak amplitude of 0.01–1 (default 0.4), onset fixed at
0.8 seconds and base frequency 200–2900 Hz. R07 adds message-level orchestration,
WAV export and independent clean-waveform checks through pinned desktop LDPC/CRC.
Phone-exported WAV validation remains pending; see [the R07 evidence and limits](OFFLINE_WAVEFORM.md).
These settings do not establish TX scheduling or hardware output calibration.

`dsp:ft8` runs JNI on `Dispatchers.Default`, with one process-wide mutex. Native
calls own their buffers and have no shared message/hash state. Decoder search is
200–3000 Hz, 2× time/frequency oversampling, at most 60 candidates and 25 LDPC
iterations per candidate. Frames require zero parity errors and matching CRC.
Duplicate payloads are collapsed within a slot. A valid channel frame does not
establish a plausible CB message or a QSO peer.

The sync score is **not SNR**. No fabricated SNR or absolute UTC is returned.
Cancellation is observed around calls, not inside native search. Queue policy,
interruptible decoding, weak/noisy sensitivity, multiple-signal handling,
false-positive rates and thermal/battery behavior are R08/R09 work. The small
upstream monitor still uses its original C allocation behavior; production
memory-pressure recovery needs qualification before live reception.

## Independent desktop evidence

[`channel-sources.json`](../reference/wsjtcb/channel-sources.json) pins nine
additional original WSJT-CB files at
`94f776d54946fe43db5e0bb9c966b1595c7b7d97`. The test harness calls the original
`encode174_91`, `get_ft8_tones_from_77bits` and `gen_ft8wave`; no channel algorithm
is stubbed. Linker section removal discards unused legacy routines.
Boost headers implement the original desktop CRC wrapper, host-only.

[`channel.json`](../reference/wsjtcb/channel/channel.json) now contains **131
unique payloads** from the R03b/R03c corpora, including binary raw RX test
cases, with exact 174-bit codewords
and 79-tone sequences. The native check compares every payload/tone and reverses
Gray coding to verify every codeword. Four committed clean WAVs are generated
independently by the desktop routines: CQ, both type-4 hash orientations and a
roger report. Each is mono PCM16, 12 kHz, 15 seconds, base 1000 Hz and onset 0.8 s.
The fixtures establish raw channel compatibility, including payloads whose text
meaning is lossy or inadmissible; they do not certify CB text acceptance.

Reproduce on a host with GNU Fortran, C++17, Boost headers, CMake and Ninja:

```bash
python3 scripts/wsjtcb_channel.py --check
cmake -S native -B .tooling/native-host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build .tooling/native-host --parallel 2
python3 scripts/check_native.py .tooling/native-host
```

On this Linux PC run each heavy command through
`bash scripts/controlled.sh 120 COMMAND ...`. The SDK CMake binary is under
`$ANDROID_HOME/cmake/3.22.1/bin`; add it to PATH. The locally extracted Fortran
setup is `.tooling/qso-env.sh`; also set `BOOST_INCLUDEDIR` to
`.tooling/reference-boost/root/usr/include` using an absolute path.
A system installation of gfortran/Boost needs no such overrides.
The checker regenerates exact channel bits/tones and compares desktop PCM with
an eight-unit PCM16 tolerance for Fortran/libm rounding. Committed WAV SHA-256
values must match exactly. Native tests additionally cover own-encoder roundtrip,
silence and invalid input. Independent WAV decoding is the interoperability gate;
the own-encoder roundtrip alone is insufficient.

## Reproduce on a phone

`engineprobe` is a separate APK with no audio, USB or network permissions and no
launcher activity. It uses the same native library as the main app. Installing
it leaves the main app and station settings intact. It packages test WAVs that
are excluded from the main APK.

```bash
source scripts/env.sh
bash scripts/controlled.sh 600 bash scripts/check.sh
bash scripts/probe_phone.sh
```

Expected result: `PASS`, four desktop WAVs decoded to exact payloads, matching
time/frequency within 0.16 s / 6.25 Hz, successful encode/decode, no silence frames
and rejection of NaN frequency. The instrumentation reports encode and per-WAV
decode milliseconds, post-test process PSS and allocated native heap. **Post-test
PSS is not peak memory**, and clean offline fixtures are not a sensitivity or
sustained-operation benchmark. GitHub CI builds both APKs and checks host
references; it does not run a physical phone or prove radio interoperability.

## Phone evidence

Measured on **Samsung Galaxy A51 SM-A515F, Android 13, arm64-v8a**, 2026-11-05,
using the debug probe APK, disconnected from radio/audio hardware:

| Check | Result |
| --- | --- |
| Independent desktop WAVs | 4/4 exact payloads; time/frequency tolerances passed |
| Per-WAV decode | 151, 147, 180, 172 ms |
| Own encoder | 37 ms; emitted PCM decoded to the original payload |
| Silence / invalid frequency | No frames / rejected |
| Post-test process PSS | 45,135 KiB (about 44.1 MiB), not a peak |
| Post-test allocated native heap | 7,705,296 bytes (about 7.3 MiB), not a peak |

The runner force-stops only its own probe package on exit, including timeout.
The main app and its station settings were left intact. This is R04's offline
ARM64 smoke gate, not a live RX, calibrated SNR or radio interoperability result.

Tested debug probe APK SHA-256: `46c3d9603cee4fb4ad1cb9ef6f441912377ee58d279591913954b71b89a426ce`.

R08 extends frame metadata with a nullable experimental 2500 Hz SNR estimate and
corrects the fixed 160 ms FFT-window onset delay. The clean/noisy recorded-slot
corpus checks onset, payload and SNR against supplied reference context; see
[recorded-slot qualification and limits](RECORDED_SLOTS.md). The JNI result now
includes a float SNR array; NaN maps to an unavailable Kotlin value.

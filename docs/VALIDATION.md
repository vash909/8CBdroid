# Implementation and validation record

This record includes **PR #29 (spectrum/waterfall)** on **2026-11-07**.
It records observations at specific commits; it is not a release
qualification or a statement that all possible checks passed.

## Implemented capabilities

| Area | Implementation | Evidence / remaining boundary |
| --- | --- | --- |
| Station settings | Persistent callsign and locator, explicit save/read errors. | JVM persistence tests and phone reopening. Other operating settings remain placeholders. |
| LAN | Heartbeat/status, live/replayed decodes, bounded history and explicit Replay/Clear. | Wire/loopback/ViewModel tests and accepted LAN changes. Full desktop log/command integration is absent. |
| USB discovery | Device descriptors, recognized serial drivers and explicit Android permission requests. | Permission-state JVM coverage; actual OTG/CAT/radio interoperability remains unverified. |
| CB codec and hash sessions | Stateless packing/unpacking plus isolated, bounded RX hash learning with explicit ambiguity. | Desktop-derived JVM/phone fixtures. Full decoder plausibility and QSO admission are separate concerns. |
| Generated audio file | Message-to-PCM and Android WAV export. | R07 independent channel qualification and A51 generation/export. No playback or TX scheduling. |
| Speaker preview | Explicit phone-speaker playback, gain/ramps, focus and cancellation. | R14 A51/Android 13 checks: two complete slots, physical PC-microphone recordings decoded by the pinned desktop known-time/frequency adapter, plus UI/lifecycle checks. Bench only. |
| One-shot UTC output | Frozen message/clock/output, measured latency and even/odd bench slots. | Virtual-clock/ViewModel tests and real speaker/onset checks described in [the scheduling guide](UTC_AUDIO_SCHEDULING.md). No radio PTT/VOX qualification. |
| Recorded reception | WAV import, supplied UTC, CB interpretation, onset/DT/offset and estimated SNR. | R08 host reference comparisons, A51 probe and real document-picker/UI checks. Complete desktop decoder parity remains unverified. |
| Microphone capture | Selected foreground input, levels, bounded PCM, resampling and diagnostic export. | R10 built-in A51 microphone and acoustic PC/phone tone checks. |
| UTC receive slots | Complete timestamped windows using measured NTP/GPS app time. | R11 A51/S25 FE checks below; incomplete or interrupted windows are discarded. |
| Live local reception | Automatic complete-slot decoding, bounded work/history and per-run CB hash learning. | R12 desktop-speaker to A51-microphone reception plus real UI/lifecycle checks below. Foreground RX only. |
| Spectrum and waterfall | 2 Hz microphone display, 96-row history, zoom/pan, independent RX/TX selection and decoded-frequency markers. | A51 physical-tone, acoustic FT8 and UI checks below. Cursors do not filter, tune or transmit. |

There is no implemented CAT/PTT,
live radio TX,
AutoSeq, QSO log or sustained background operating service.

## Reproducible coverage

| Corpus/check | Scope |
| --- | --- |
| 240 complete-call + 75 type 4 predicates | Pinned desktop text admissibility. The complete-call results are compared with the Kotlin validator. |
| 82 packing cases + 15 hash vectors + 96 RX scenarios | Desktop payloads, cold/warm hashes, collisions and state boundaries. |
| 129 QSO traces + 72 generated-message payloads | Original desktop parser/generation/AutoSeq methods in a host adapter, not Android automation or a full TX lifecycle. |
| 154 message cases | Kotlin exact payload/cold-text or explicit rejection checks on JVM and phone. |
| 96 hash-session scenarios | Shared JVM/phone adapter; documented collision outcomes deliberately differ from desktop last-writer lookup. |
| 131 channel vectors + 4 clean WAVs | Exact codewords/tones and independently generated desktop audio. Includes payloads whose text can be unsupported or lossy. |
| 21 generated PCM cases | Seven payloads at three frequency/level pairs; original desktop LDPC/CRC/unpacker with supplied synchronization, plus waveform measurements. |
| 12 recorded-slot cases | Four clean, four AWGN, two onset shifts, corrupted data with intact sync and noise-only rejection. AWGN SNR estimate within 3 dB at the injected test levels. |
| 20 acoustic events + RX reset | Reproducible numeric/slash exchanges, cold/warm hashes, degraded input and foreign finals; physical acceptance results are recorded in [PR #37](https://github.com/vash909/8CBdroid/pull/37). |

At the R08 tested commit, the Android check reported **755 JVM tests** (678 core,
32 protocol, 45 app), lint success and both debug APKs built. The separate
reference job also passed **41 Python tests** and the native/reference checks.
These are revision-specific counts; use current CI reports for later changes.

Reproduction commands and host dependencies are centralized in
[development](DEVELOPMENT.md#desktop-reference-and-native-checks). Fixture
provenance is in [the reference directory](../reference/wsjtcb/README.md).

## Samsung A51 result: R08

Device: **Samsung Galaxy A51 / SM-A515F, Android 13, ARM64**. No radio/audio
hardware was connected. The tested APKs came from
[CI run 42235684450](https://github.com/vash909/8CBdroid/actions/runs/42235684450),
PR head **`d2634e03ce36a6060dfc5c52c572bac8fc1e629d`**.
[PR #23](https://github.com/vash909/8CBdroid/pull/23) was merged as
`9744a2e1b86a82483eb9c4d1aeaa8c205c165cd5` after review.

Artifact ZIP digests, PR/run commit and installed APK hashes were verified.
A signing conflict required a reinstall; persistent app
files were backed up and restored byte for byte before launch.

| Probe result | Observation |
| --- | --- |
| Text / hash checks | 154 message cases and 96 hash-session scenarios passed. |
| Original desktop WAVs | Four exact payload decodes; 143, 164, 208, 200 ms. |
| Generated waveforms | 12 encode/decode cases passed; CQ encode 35 ms. |
| Recorded-slot cases | 12/12 passed through the production WAV reader and recorded decoder. |
| Recorded-slot times, corpus order | 160, 170, 165, 173, 200, 170, 184, 173, 144, 168, 163, 133 ms. |
| Post-test memory | PSS 31,786 KiB; allocated native heap 7,209,544 bytes. Neither is a peak measurement. |

The real UI/document-picker checks passed for:

- Empty UTC and an off-boundary UTC rejected before import.
- Clean CQ WAV: `CQ 1AT106`, offset 1000 Hz, DT +0.30 s and signal UTC +0.800 s.
- Noisy CQ WAV: same text, estimated SNR -3.8 dB against injected -4 dB.
- Corrupted data and pure noise: no valid frames.
- Short WAV: explicit sample-count error; subsequent valid import succeeds.
- Picker cancellation and retry, rotation with result retained, and restoration
  of the phone's original rotation setting.
- Force-stop/reopen: station preserved, transient recording/UTC discarded, then
  a fresh clean import succeeds.

These are short debug-build observations, not peak-memory, sustained-load,
thermal, battery, sensitivity or false-positive-rate measurements.

## R07 export evidence

[PR #22](https://github.com/vash909/8CBdroid/pull/22), tested head
`f0ff8c40eabbbe05c0111d937a67c2b35c5069d3`, used
[CI run 42200352693](https://github.com/vash909/8CBdroid/actions/runs/42200352693).
The A51 passed 12 generated waveforms, message/hash checks and four desktop WAVs.
UI checks passed generation, document export, invalid frequency/level,
loss-consent/reset, invalidation after edits and reopening.

The exported CQ WAV was **360,044 bytes**, SHA-256
`2aca24c402918763725ca19547197ee79b78a7043b3afd12e11ac79fb38d5618`.
It matched the phone probe's generated file byte for byte and passed the
original desktop channel routines through the known-time/frequency adapter.
This did not run the complete WSJT-CB desktop GUI decoder.

### R07 closure checks (PR #26)

On 2026-11-06 the same A51 passed the remaining generation/export checks:
rotation retained the generated result; cancelling the document picker retained
Save WAV, and a subsequent export succeeded. Original rotation settings were
restored. CQ and both directed hash orientations were exported through the real
Android picker at 1000 Hz / 40%, each as 360,044-byte PCM16 WAV.

| Message | Actual exported WAV SHA-256 |
| --- | --- |
| `CQ 1AT106` | `2aca24c402918763725ca19547197ee79b78a7043b3afd12e11ac79fb38d5618` |
| `<1AT106> 26AT101` | `602162e18be80226b6d604b6578ab1db7b74138cd46a9f0af5f3d0f6ebcc425b` |
| `26AT101 <1AT106>` | `3d7926fa841c2053f285b80eaa77742af94aa7b0cbd8d05791fd42b0cbb38eaf` |

The actual phone exports were opened through **File → Open** in the complete
installed **WSJT-CB 1.4.1** application. Its full FT8 file decoder displayed
`CQ 1AT106`, `<1AT106> 26AT101` and `26AT101 <1AT106>`, respectively, at 1000 Hz
and DT +0.3 s. CQ was loaded first to teach the hash. Files were copied without
PCM conversion and given UTC basenames `261106_120000.wav`, `261106_120015.wav`
and `261106_120030.wav`. An isolated desktop profile was used; radio, Tune and
TX were not exercised. The window's default band label does not establish a
27 MHz radio test.

Desktop binary SHA-256 (`/usr/lib/wsjtcb/bin/wsjtcb`):
`76337a8f5114256ce122feefdf60a28bbdce437234ada9fe33c8481fb4282e5d`.
Decoder binary SHA-256 (`jt9`):
`c7ecfe58e6f47f0daeab4239a956ee2bbb55e290f1f5163ff5043018d8b0bcd9`.
The same WAVs also passed the pinned channel adapter's exact payload/tone,
duration, level, ramp and limited spectral checks. Cold hashes remained `<...>`
there, as expected; the warm desktop session resolved them.

The new test-only instrumentation passed a real ContentResolver write failure,
visible error and successful retry with the exact CQ hash above. It also passed
the actual Cancel generation control with bounded queued work, cancellation
observed during native encoding, cleanup/no stale result, generation retry and
actual text-field rejection of `<...> 26AT101` with Generate disabled. Only the
queued-control check temporarily parks dispatcher workers; the separate active
native cancellation check uses the real encoder. No production backend is mocked.

The closure test was first qualified locally on the A51; the final verified CI
commit/run, APK hashes and repeated device results are recorded in
[PR #26](https://github.com/vash909/8CBdroid/pull/26). See
[the reproducible procedure](DEVELOPMENT.md#r07-device-checks).
This closes the outstanding R07 file-generation checks, not noisy reception,
RF interoperability, playback or a complete QSO.

## Samsung A51 result: R09

[PR #24](https://github.com/vash909/8CBdroid/pull/24) was merged as
`d9d797ec911c295f345151fdebc3be846f072881` after review. Its tested head
`66698752061e2198f73ec81652a6dd63d36b4864` came from successful
[CI run 42248909556](https://github.com/vash909/8CBdroid/actions/runs/42248909556).
The matching CI APKs were verified/installed on the same A51, preserving settings.

| Profile | Signals recovered | Unexpected frames | p50 / p95 / maximum |
| --- | --- | --- | --- |
| Fast | 32/34 | 0 | 148 / 235 / 246 ms |
| Standard | 32/34 | 0 | 160 / 257 / 275 ms |

Each profile used 40 fixed slots, including six negative controls. Five actual
native cancellations released in 5–17 ms; queued cancellation and third-request
rejection passed. Sampled maxima were 39,882 KiB PSS and 13,328,872 bytes native
heap, not exact allocator peaks. A 45-second soak completed 298 decodes. Android
thermal status was 0 before/after, no severe event was observed during the soak,
and reported battery temperature remained 21.4 °C at the sampled endpoints.
This finite corpus does not establish a statistical false-positive rate, usable
sensitivity or sustained-session qualification.

UI checks passed both profiles, profile/time display, stale-result invalidation,
negative WAV import, picker cancellation/retry, rotation and reopening with saved
station retained. Local validation included 762 JVM tests, lint/debug APKs and
native/reference regression checks. See [decoder bounds](DECODER_PROFILES.md).

## Samsung A51 result: R10

[PR #25](https://github.com/vash909/8CBdroid/pull/25) was merged as
`7cefb71c4cb4f3a5d2368ed57c99784df01f9abe` after review. Tested head
`eacb2d4a6a30b07ac9719c50ff4f44b5657e0c5a` used successful
[CI run 42258964880](https://github.com/vash909/8CBdroid/actions/runs/42258964880).
Local checks passed 774 JVM tests (689 core, 32 protocol, 53 app), lint and APK
builds. The verified CI APKs ran on the same A51 with settings restored byte for byte.

The built-in microphone reported 48 kHz mono PCM16 with the voice-recognition
fallback source; echo cancellation was disabled, AGC/noise suppression were
unavailable. Vendor processing remains possible. Capture converted to 12 kHz,
retained at most five seconds and exported a 120,044-byte diagnostic WAV.

The acoustic probe passed a 1000 Hz speaker-to-microphone tone and cancellation/
restart cleanup. With the nearby PC, 1500 Hz PC speaker → A51 microphone and
1000 Hz A51 speaker → PC microphone checks both passed through physical audio.
These are reference tones, not bidirectional FT8 or a radio QSO.

Main UI checks passed permission denial/retry, actual route and levels, bounded
buffer, stop/export, navigation away, rotation and background/reopening without
automatic restart. Rotation settings and saved station data were preserved.
Physical wired-headset detach testing was not performed; external inputs,
USB/wired interoperability, sustained capture and live decoding remain unqualified.
See [microphone capture](MICROPHONE_CAPTURE.md).

## R11 time synchronization and UTC capture

R11 adds NTP/GPS-only time correction, regional/custom NTP server
preferences and timestamped complete microphone slots. Manual offsets and an
unsynchronized fallback are excluded. See [UTC slots](UTC_RECEIVE_SLOTS.md).
Review builds on the Galaxy A51 (SM-A515F, Android 13) have exercised actual NTP
exchanges using an IPv4 address and a hostname, app-only correction, physical
speaker-to-microphone tone capture across two UTC windows, and a 360,044-byte
slot export. The actual UI also exercised regional/custom preferences,
permission denial/allow, measurement cancellation, cold microphone startup,
Stop and document-picker cancellation/retry. Timestamp startup and UI handoff
faults found during review have regression tests; the audio timing guard now
allows a declared 10 ms budget, also included in slot uncertainty.

The Galaxy S25 FE (SM-S731B, Android 17) passed real GPS measurement,
cancellation/retry and explicit application of the correction. With Wi-Fi and
mobile data disabled and no active default network, the actual UI also completed
a fresh GPS measurement/apply and the disabled-Location error/recovery flow.
GPS timing uncertainty remains unavailable rather than inferred from position
accuracy. These checks do not calibrate absolute satellite timing accuracy.

The S25 FE separately passed hostname NTP synchronization and physical
speaker-to-microphone capture across two complete UTC slots, reference-tone
recovery, 360,044-byte export and the unchanged-Android-clock check.

The automated offline GPS slot/export UI sequence did not finish because its UI
driver could not find the idle state after Stop. An automated complete offline
GPS slot/export pass is therefore not established. Complete-slot audio, export
and lifecycle evidence from the matching A51 build remains applicable; the
additional S25 FE capture/export probe used NTP.

Qualification must match the final CI APK. The tested commits, CI runs, device
results and qualification limits are recorded in
[PR #27](https://github.com/vash909/8CBdroid/pull/27). Previous-build evidence
does not qualify a changed backend.

GPS acquisition timed out on this A51, including outdoors. The cause has not
been diagnosed. The successful S25 FE measurements do not
qualify the A51 receiver or guarantee acquisition on other devices or sky views.

## R12 live local reception (PR #28)

Device: **Samsung Galaxy A51 / SM-A515F, Android 13**. The tested CI APKs came
from [run 42313349465](https://github.com/vash909/8CBdroid/actions/runs/42313349465),
commit **`6d5357d0e6c36e91b640f633dc25549914300939`**. Local checks and CI passed
813 JVM tests (709 core, 32 WSJT-CB, 5 NTP, 67 app), lint and debug builds.
The desktop reference job passed. Artifact digests and installed APK hashes
were verified; settings were restored byte for byte after the signing-conflict
reinstall.

The physical check used pinned desktop-generated WAVs, actual PC speakers and
the built-in phone microphone, with applied NTP time from `time.cloudflare.com`.
It supplied no PCM or decoder results to the phone and sent no LAN messages.

| Check | Observation |
| --- | --- |
| Consecutive complete slots | `CQ 1AT106`, then `<1AT106> 26AT101`; the second slot resolved the identity learned from the first. |
| Timing/work | Two complete UTC slots; last decode 157 ms. This is one measurement, not sustained-performance qualification. |
| Actual UI | The local-source heading and directed message were visible in the Audio screen. |
| Stop/restart | Results and learned calls cleared; the same directed fixture became `<...> 26AT101` after an explicit cold restart. Zero native calls remained after Stop. |
| Permission retry | Denied the real Android microphone prompt, verified the error, then granted it on retry and opened the actual recorder. |
| Profiles/mode | Fast/Standard controls, real Fast microphone capture, and disabling UTC assembly also disabling live mode passed. |
| Lifecycle | Rotation, background/reopen and leaving Audio stopped capture, cleared local results/cache and did not restart reception. |

The bounded physical helper finished in 78 seconds; the separate UI sequence
finished in 396 seconds. Temporary power/rotation settings were restored.
JVM tests also cover slow DSP/latest-slot replacement, 60-row history, unknown
and staged hashes, busy/deadline recovery, and cancellation with late results.
See [live reception](LIVE_RECEIVE.md) for reproduction and interpretation limits.
These checks establish acoustic foreground RX on this device, not RF/VOX, USB,
speaker TX, calibrated weak-signal performance or a complete QSO.

## R13 spectrum/waterfall (PR #29)

Device: **Samsung Galaxy A51 / SM-A515F, Android 13**. The qualified app and
instrumentation came from successful [CI run 42384811302](https://github.com/vash909/8CBdroid/actions/runs/42384811302),
commit **`c4ab82cc8969765f767df60890f2d8fdbe43b2cc`**. Local checks and CI passed
824 JVM tests (717 core, 32 WSJT-CB, 5 NTP, 70 app), lint, debug builds and the
desktop reference check. Artifact digests and installed APK hashes matched;
settings were backed up and restored after the signing-conflict reinstall.

The physical spectrum helper used independent 700/1900 Hz tones from actual PC
speakers into the built-in microphone, with the whole plot visible. It injected
neither PCM nor analyzer results. Plain spectrum capture required no time sync.
The display uses a fixed **2 Hz** cadence; 96 rows span about 48 seconds.

| Check | Observation |
| --- | --- |
| Sustained display | 242 analyzed windows in 120,971 ms; 96 retained rows, zero replaced windows, and progress at every one-second observation. |
| Frequency axis | Peaks at 700.1953 and 1901.3672 Hz; both tones passed all 120 observations within the 6 Hz tolerance. |
| Memory/work | PSS 155,359 KiB after a full-history warmup and 157,716 KiB at the end; maximum observed analysis 8,283 µs. |
| Actual controls | RX/TX buttons, graph tap, zoom 1×/4×, band pan, independent cursors, navigation, Stop and explicit cold restart passed. |
| Acoustic FT8 | NTP from `ntp.metas.ch`; received `CQ 1AT106` then `<1AT106> 26AT101`, selected the visible decoded-frequency marker into RX, and verified local-source text. Three complete slots; last decode 198 ms. |
| Session cleanup | Stop cleared results/history/hash learning and left zero native calls. A directed fixture after restart decoded as `<...> 26AT101`. |
| Separate UI checks | Real permission denial/grant retry, diagnostics expand/collapse, slider TX selection at 2220 Hz with RX unchanged, rotation, background/reopen and process recreation passed. |

The separate UI sequence used CI run 42383372135 / commit
`5bb1865baaf1239e68de57ffac9774e312c59f89`. Every unsigned app/engine APK entry
matched the qualified build; the subsequent change affected instrumentation
scrolling only, and both affected acoustic probes were repeated successfully.
The spectrum, live FT8 and separate UI helpers finished in 140, 101 and 342
seconds respectively. Final documentation-only CI APK identity and deployment
are recorded in [PR #29](https://github.com/vash909/8CBdroid/pull/29).

Process CPU during the two-minute spectrum check was 142,934 ms (about 1.18 CPU
cores on average). Android graphics recorded 352 frames after warmup, p90 81 ms
and 99.43% janky frames against Android's animation deadlines. These are debug
build diagnostics: the acceptance checks establish regular 2 Hz updates and
working controls, not 60 fps animation, battery efficiency or thermal endurance.
Memory measurements are snapshots, not peak or long-session qualification.

See [the spectrum guide](SPECTRUM.md) for reproduction, levels and display-only
cursor limits. This milestone does not qualify speaker TX, RF, USB or a QSO.

## Coordinated phone audio bench (PR #35)

On Samsung A51 / SM-A515F, Android 13 / ARM64, tested commit
`fb5ba9713144ea9e4258ab752b6e20ca2d290769` with successful CI run
[42689390307](https://github.com/vash909/8CBdroid/actions/runs/42689390307).
The matching app/test/probe APKs were verified by artifact and installed hashes.
Local checks passed 899 JVM tests, lint and debug builds.

Physical PC-speaker to phone-microphone reception passed before output and after
each of two scheduled even/odd phone-speaker slots. Silent latency calibration
also recovered an existing receiver. The microphone stayed closed during queued
waiting/playback; attempted starts were rejected, no receive DSP ran, and resumed
sessions discarded partial slots, old results and learned calls.

Both PC-microphone captures independently decoded `CQ 1AT106`, with matching
77-bit payload and all 79 observed tones through the pinned desktop adapter.
Estimated acoustic first-tone errors against UTC slot +500 ms were +24/+45 ms;
nearby PC capture anchors spread 1.8/9.4 ms. Neither active signal clipped.
Android presentation estimates were +7.8/+11.6 ms, with 180,000 frames per slot.
These are finite bench measurements, not guarantees of DAC or acoustic timing.
NTP uncertainty and the uncalibrated host microphone path remain relevant.

Stop, explicit microphone Stop, real separate-app focus loss, correction reset,
message edit, navigation, retained Activity recreation and profile cleanup
passed. Errors/cancellation did not resume capture; profile application cleared
recovery consent. See [the phone audio guide](PHONE_AUDIO.md) for reproducible
checks. Radio VOX/PTT, RF QSOs, USB audio and continuous background operation
are outside this evidence. Later changes and final APK checks are recorded in
[PR #35](https://github.com/vash909/8CBdroid/pull/35).

## Manual acoustic operation (PR #36)

Samsung A51 / SM-A515F, Android 13 / ARM64, ran matching CI app/test/probe APKs
for `700b6606a5cd9552d8767f1dfa1240e5862b69e1`, successful CI
[42703999857](https://github.com/vash909/8CBdroid/actions/runs/42703999857).
Local checks passed 972 JVM tests, lint and debug builds. Sixty-one manual
text/payload expectations match the pinned independently generated desktop corpus.
See [manual operation](MANUAL_OPERATION.md) for setup and limits.

The physical PC-speaker/phone-microphone scenario received desktop-reference CQ,
directed reply, report and RR73 messages. Operate's actual peer selection,
calibration, Enable TX and Send one slot controls emitted reply, R-report and 73
through the phone speaker. Each completed 180,000-frame slot disarmed TX and
recovered the previously active receiver with old results/learned calls cleared.
Warm receive context and fresh sessions after output were observed. This basic
scenario used even output slots; both scheduler periods have separate earlier
phone evidence, and the manual ViewModel's odd-slot selection is JVM-tested.

All three actual PC-microphone recordings passed the pinned desktop known-time/
frequency LDPC/CRC adapter with exact expected 77-bit payloads and all 79 observed
tones. The reply's destination remains `<...>` in the deliberately unseeded
adapter; its wire payload matches `<26AT101> 1AT106`. The other texts decoded as
`26AT101 R-12` and `26AT101 73`. Estimated acoustic onset errors against UTC slot
+500 ms were +56.7/+74.2/+70.2 ms, with nearby capture-anchor spreads
1.7/20.8/14.4 ms. These are finite acoustic bench observations, not DAC guarantees.
NTP RTT was 23.3 ms with reported uncertainty 22.6 ms.

Queued Stop, Stop during actual playback, edits, correction reset, real separate-
app focus loss, navigation, retained Activity recreation and profile cleanup
passed. Playback Stop cleanup took 111 ms in this observation; interrupted audio
was not added to Sent. The explicit two-second test tone recovered RX without
adding a message. Field/source and system microphone-permission denial/retry
checks passed on an application payload identical to this tested revision.

An earlier receive attempt observed a 338.9 ms hardware timestamp departure.
The existing 10 ms guard stopped capture and discarded the incomplete audio;
an explicit new session completed the full scenario. The guard was not relaxed.
This does not characterize long-session capture reliability. Further final APK/
UI checks are recorded in [PR #36](https://github.com/vash909/8CBdroid/pull/36).
No radio, RF QSO, CAT/USB qualification, AutoSeq or persistent QSO logging is
established by this acoustic scenario.

## Broader acoustic interoperability suite (PR #37)

The [acoustic bench guide](ACOUSTIC_INTEROP.md) defines the scenario inputs,
independently generated desktop expectations and physical reproduction steps.
Six phone outputs cover both even/odd periods and both initiating/answering roles.
Fourteen incoming messages include an 18 dB attenuated CQ, deterministic noisy
input, both hash orientations and finals for other calls. Every received message
must leave manual peer/message selection and TX state unchanged.

Acceptance requires physical microphone/speaker exchanges and independent
decoding of all six phone recordings. The installed WSJT-CB `jt9` searches the
timestamp-derived UTC recordings; the pinned known-time/frequency adapter
separately checks exact payloads and observed tones. Synthetic preflight checks
alone cannot satisfy this bench. Tested commits, CI artifacts, device identity,
measurements and outcomes are recorded in [PR #37](https://github.com/vash909/8CBdroid/pull/37).
The suite renews test time at safe idle boundaries when needed; it does not add
automatic NTP refresh to the client or change its ten-minute correction expiry.
No RF contact, AutoSeq or sustained-operation qualification follows from this
finite acoustic matrix.

## CB QSO domain progression

The [JVM domain model](CB_QSO_PROGRESSION.md) separates observed messages,
qualified receptions, prepared output and successful sent callbacks. Its test-only
six-message relay uses pinned desktop-generated text/payloads; Android completion
expectations are explicit policy, not evidence of desktop audio completion.
Five shared safety scenarios also run in the separate Android probe. Broader JVM
cases cover both roles, numeric/slash calls, report bounds, duplicate/late events,
abort, peer replacement and stale tickets. Device/CI results belong in the feature
PR; neither these tests nor the model connect live decoding or automatic audio.

## Checks still pending

- Wider/noisier and multiple-signal reception, calibrated SNR, false-positive
  rates and large overlapping-signal workloads beyond the finite reference and
  acoustic corpora.
- Sustained performance, memory peaks, thermal/battery behavior and other phones.
- Physical OTG permission/detach, a documented radio/CAT connection, USB audio,
  CAT/PTT and actual radio/RF observations. Radio VOX is operator-configured. Bench speaker scheduling
  and phone audio recovery are covered separately above.
- Live AutoSeq admission, next-message decisions, automatic audio execution and
  durable QSO logging. The isolated domain model above does not enable these paths.

A JVM test, a successful APK build, an engine probe and a radio bench test are
separate evidence. Report each with its actual scope when updating this record.

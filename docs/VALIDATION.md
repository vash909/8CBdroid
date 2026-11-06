# Implementation and validation record

This record describes the repository through merged **PR #24 (R09)** on
**2026-11-06**. It records observations at specific commits; it is not a release
qualification or a statement that all planned checks passed. The
[roadmap](ROADMAP.md) remains the delivery plan.

## Merged capabilities

| Area | Implementation | Evidence / remaining boundary |
| --- | --- | --- |
| Station settings | Persistent callsign and locator, explicit save/read errors. | JVM persistence tests and phone reopening. Other operating settings remain placeholders. |
| LAN | Heartbeat/status, live/replayed decodes, bounded history and explicit Replay/Clear. | Wire/loopback/ViewModel tests; owner accepted the LAN PRs. Full desktop log/command integration is absent. |
| USB discovery | Device descriptors, recognized serial drivers and explicit Android permission requests. | Permission-state JVM coverage; actual OTG/CAT/radio interoperability remains unverified. |
| CB codec and hash sessions | Stateless packing/unpacking plus isolated, bounded RX hash learning with explicit ambiguity. | Desktop-derived JVM/phone fixtures. Full decoder plausibility and QSO admission are separate concerns. |
| Generated audio file | Message-to-PCM and Android WAV export. | R07 independent channel qualification and A51 generation/export. No playback or TX scheduling. |
| Recorded reception | WAV import, supplied UTC, CB interpretation, onset/DT/offset and estimated SNR. | R08 host reference comparisons, A51 probe and real document-picker/UI checks. No live capture or complete desktop decoder parity. |

R10 microphone capture is under review below. There is no implemented CAT/PTT,
speaker output, live local RX/TX,
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
`9744a2e1b86a82483eb9c4d1aeaa8c205c165cd5` after owner approval.

Artifact ZIP digests, PR/run commit and installed APK hashes were verified.
A signing conflict required an explicitly approved reinstall; persistent app
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

R08's picker cancellation/rotation tests concern recorded-file import. They do
not establish that the separate R07 generation/export cancellation, rotation
and failed-save paths were tested on the phone.

## Samsung A51 result: R09

[PR #24](https://github.com/vash909/8CBdroid/pull/24) was merged as
`d9d797ec911c295f345151fdebc3be846f072881` after owner approval. Its tested head
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

## R10 microphone capture under review

The branch implements selected foreground microphone capture, route/processing
reporting, input peak/RMS/clipping, bounded recent PCM, hardware-rate conversion
to 12 kHz and explicit diagnostic WAV export. It adds JVM signal/session tests
and an opt-in acoustic phone probe. See [microphone capture](MICROPHONE_CAPTURE.md).
Actual R10 CI/device results are recorded in its feature PR before merge.
The owner has no wired headset available for physical detach testing; built-in
microphone qualification must not be presented as USB/wired interoperability.

## Checks still pending

- Import the actual exported WAV in the full WSJT-CB desktop decoder, including
  both directed hash orientations. Known-time/frequency host checks are narrower.
- Remaining R07 generation/export device checks: rotation, generation/picker
  cancellation, failed-save retry and explicit unresolved-input UI rejection.
- Wider/noisier and multiple-signal reception, calibrated SNR, false-positive
  rates and large overlapping-signal workloads beyond the finite R09 corpus.
- Sustained performance, memory peaks, thermal/battery behavior and other phones.
- Physical OTG permission/detach, a documented radio/CAT connection, USB audio,
  acoustic VOX, scheduling, PTT and complete radio QSOs.
- Android AutoSeq admission/progression and durable QSO logging.

A JVM test, a successful APK build, an engine probe and a radio bench test are
separate evidence. Report each with its actual scope when updating this record.

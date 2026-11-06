# Decoder work bounds and profiles (R09)

R09 bounds offline engine admission, adds cooperative cancellation inside native
work, and exposes two recorded-slot profiles. It does not enable a microphone,
playback, live RX or RF transmission. The baseline device is **Samsung A51 /
SM-A515F, Android 13, ARM64**. Hardware and wider reception qualification remain
separate from the finite checks described here.

## Admission and cancellation

Every `NativeFt8Engine` instance uses one process-wide `Ft8WorkGate` for encode
and decode together: **one active call and one FIFO waiter**. A third request
fails immediately with a visible busy/retry error. Admission occurs before
Default-dispatcher work, PCM copies and native handles. Cancelling a waiter
removes it; cancellation/failure releases capacity only after active cleanup.
There is no unbounded request channel or background worker owned by the gate.

The JVM cancellation callback signals a per-call atomic native flag while JNI
runs on its worker. Native IDs refer to mutex-protected shared ownership, so a
cancel/release race cannot free a live request. No global cancellation flag can
abort a newer request. Native handles are released on success, cancellation,
validation failure and deadline failure.

Checks occur at preparation, each FFT block, before/after candidate search,
between candidate decodes and each symbol used for SNR. Encoding checks between
symbol/chunk operations too. The unchanged vendor FFT/search/LDPC routines are
not interrupted midway through an individual call; cancellation is cooperative,
not an operating-system hard kill. A cancelled/timed-out request publishes no
partial results. Existing ViewModel version checks also reject stale results.

Each call has a monotonic native deadline. Deadlines begin when native work
starts, excluding dispatcher/queue waiting, file-provider I/O and Java/Kotlin
preparation. They are checked at work boundaries, so scheduling pauses and one
in-progress vendor operation can delay observation. A blocking Android document
provider must still return before its read ends. R09 does not provide a universal
end-to-end import timeout or memory-pressure recovery.

## Profiles

| Profile | Candidate cap | LDPC iteration cap | Native deadline |
| --- | --- | --- | --- |
| Fast | 24 | 12 | 2 seconds |
| Standard | 60 | 25 | 4 seconds |

Standard preserves R08's search/iteration limits. All profiles retain the same
200–3000 Hz search, 2x time/frequency oversampling, CRC/parity requirements,
payload deduplication and CB interpretation. Encoder work has a 2-second native
deadline. No profile weakens integrity checks or enables desktop decoder filters.

The **Audio** tab selects the profile for the next explicit import. Changing it
clears old results; the request snapshots profile, station and UTC. Results show
the used profile and decode milliseconds. Profile choice survives rotation but
is not a saved station setting. Fast can miss weaker signals. A larger experimental search budget did not
recover additional payloads in the fixed corpus and was discarded. Elapsed
decode time excludes file reading
and gate waiting, and includes JNI input/result conversion.

## Minimum finite reception corpus

[`benchmark.json`](../reference/wsjtcb/recordings/benchmark.json) pins the source
revision, profile limits, seeds and PCM16 hashes for **40 slots per profile**:

- The 12 R08 recorded cases: ten injected signals and two negative controls.
- 24 weaker derivatives: three original desktop CB waveforms, each at -8/-12/
  -16/-20 dB in 2500 Hz and two fixed seeds. Ground truth is the known injected
  payload, not the output of the decoder under test.
- Four extra negatives: two noise-only slots and two noise-plus-unmodulated-tone
  slots (300/1500 Hz). Together there are **34 signal opportunities and six
  negative slots** per profile.

The added noise is deterministic XorShift32 with twelve summed uniform draws,
unit variance and approximately Gaussian shape. It is not exact AWGN; R08's
separate four AWGN fixtures remain unchanged. Signal gain and noise scale are
fixed, with no clipping or normalization. Python host and Kotlin phone generators
must match each manifest PCM16 hash before a case is decoded. Synthetic audio is
created one slot at a time; it is never a main-app dependency or a personal recording.

Any unexpected payload fails the finite corpus. Clean original WAVs must decode
in every profile; Standard must also retain the R08 positive cases. Weaker
signals may be missed and are counted. These observed counts do not establish
a minimum usable SNR, a statistical false-positive rate or desktop decoder parity.
Two fixed noise seeds per SNR are too few for a sensitivity curve.

During exploratory local qualification, Fast and Standard each detected
**32/34** signal slots, with no frames in the six negative controls and no
unexpected payloads. The host CLI includes process startup; its wall time is
not phone decode latency. Device results are recorded in the feature
PR's validation after installing its matching CI APK.

## Reproduce host checks

After [host/native setup](DEVELOPMENT.md#desktop-reference-and-native-checks):

```bash
source scripts/env.sh
bash scripts/controlled.sh 120 .tooling/native-host/control_checks
bash scripts/controlled.sh 240 python3 scripts/ft8_benchmark.py .tooling/native-host --report .tooling/host-profile.json
```

The control check cancels after work has started in each phase, verifies cleanup,
deadline expiry and profile bounds. JVM tests cover FIFO admission, rejection,
cancelled waiters, failure cleanup and profile/input snapshots. To deliberately
regenerate the manifest, add `--generate` to the controlled benchmark command
and review the hash/case changes. CI checks the committed manifest without regeneration.

## Device procedure and limits

Deploy the verified successful CI app/probe APKs, preserving station data as in
[development](DEVELOPMENT.md#deploy-and-test-on-a-phone). The agent notifies the
owner before UI checks need an unlocked screen. Then run:

```bash
source scripts/env.sh
ADB_LIBUSB=1 bash scripts/controlled.sh 240 bash scripts/profile_phone.sh
```

This helper uses the installed probe; it does not install or uninstall anything.
Instrumentation is bounded to 180 seconds. It runs the ordinary regression
probe, all 40 cases for each profile, five active-native cancellation/queued-job
checks, and **up to 45 seconds or 500 Standard decodes** of the fixed clean CQ slot.
Severe thermal status stops qualification. It reports:

- Per-case reception, false frames, candidate counts and decode time; profile
  p50/p95/maximum times with all cases included.
- Cancel-to-native-release time, rejected third requests, cancelled waiters and
  absence of retained native handles; each release must finish within one second.
- PSS and native heap maxima **sampled every 50 ms**, including corpus preparation.
  These are sampled maxima, not exact allocator peaks or a memory-safety guarantee.
- Android thermal status and battery temperature before/after. Battery temperature
  is not CPU/SoC temperature. A short soak cannot certify sustained RF operation,
  throttling behavior, battery life or all Android devices.

The checksum-verified full report is `.tooling/profile-report.json`; stdout is
`.tooling/profile-output.txt`. Publish the tested commit/run, model/Android,
coverage counts, latency, sampled memory and thermal observations with their
scope in PR validation. Keep personal data and device serial numbers out of reports.

For UI review, switch Fast/Standard, import the same known WAV and confirm
the result's profile/time. Change profile after a result, cancel/retry an import,
rotate, and reopen. Old results must not reappear; saved station settings persist.
A successful benchmark does not replace these UI checks or owner merge approval.

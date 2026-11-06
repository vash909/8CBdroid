# Recorded FT8 slots (R08)

The **Audio** tab imports a recorded WAV through Android's document picker and
runs the offline native decoder. No microphone, playback, radio access, live RX,
automation or QSO completion is enabled.

## Input and results

- Supply the recording's **slot start in UTC**, for example
  `2026-11-06T12:00:00Z`. Seconds must be 00, 15, 30 or 45 with no fractional part.
  This is operator-supplied context, not a measured synchronization result.
  Document names and modification dates never supply UTC implicitly.
- Select one complete **15-second, 12,000 Hz, mono PCM16 RIFF/WAVE** file.
  Maximum document size is **1 MiB**, including metadata. The JVM reader handles
  bounded unknown chunks and odd-byte padding; it rejects duplicate format/data
  chunks, inconsistent RIFF lengths, unsupported encodings and incomplete slots.
  There is no automatic downmixing, resampling, padding or truncation.
- Results retain the 77-bit payload, slot/signal UTC, audio offset, DT, sync score,
  experimental SNR and message/hash flags. A channel frame must pass native
  LDPC and CRC. The R05 CB codec interprets supported messages; unsupported
  payload types remain explicitly marked instead of becoming fabricated text.
- Each import uses an isolated R06 hash session with the saved station as context.
  Unknown hashes remain `<...>`. Files never teach the LAN, preview or future
  recording sessions. A frame does not resolve its own pending learned calls.
- Changing UTC or the saved station clears results. Cancel discards late results;
  one request is admitted at a time. Rotation retains the ViewModel, while process
  termination discards the document/result. Documents are read only for the
  request, without retaining a persisted URI permission or copying private audio
  into app storage. Saved station settings remain separate.

The native FFT analysis has a trailing-window delay of one 160 ms symbol with
its fixed 2x time/frequency oversampling. R08 corrects the reported onset for
that delay. DT is **corrected onset minus 0.5 seconds**, with an 80 ms search grid;
absolute signal UTC adds the corrected onset (rounded to milliseconds) to the
supplied slot boundary.
This is coarse search timing, not precision clock calibration.

## SNR estimate and decoder limits

SNR is labeled **estimated**, in a 2500 Hz reference bandwidth. For each recovered
symbol, a Hann-windowed 80 ms center measures the recovered tone and four noise
probes outside the eight-tone band (-150/-100/+150/+200 Hz relative to base).
The estimator subtracts average probe power and applies the Hann equivalent-noise
bandwidth. It returns unavailable when no usable noise floor or positive signal
estimate exists. Narrowband interference, overlapping signals, timing/frequency
quantization and colored noise can bias it. Clean digital silence is not a
calibration source; a PCM quantization floor may yield a very large estimate.

This is **not WSJT-CB's calibrated SNR estimator or complete decoder**. The native
backend retains its bounded 60 candidates, 25 LDPC iterations, 200–3000 Hz search
and payload deduplication. CB message packing/unpacking/hash behavior uses the
pinned reference; full desktop candidate search, plausibility filters,
sensitivity and false-positive parity are not established. Do not use this
output as permission to advance a QSO. Production queue/cancellation policy,
sustained decoder cost and a broader reception corpus remain future qualification before live capture is connected.
Cancellation currently discards work around JNI calls, not during a native call;
a document provider must return for a blocking read to finish.

## Independent evidence

`reference/wsjtcb/recordings/recordings.json` records provenance, hashes, UTC,
expected payload/text, onset and noise parameters. Four clean source WAVs were
created by pinned WSJT-CB waveform routines. Four deterministic AWGN derivatives
have -4/-8/-12/-8 dB injected SNR in 2500 Hz; no clipping or normalization
hides their noise.
Two time-shifted copies test onset correction at 0.32 and 1.28 seconds.
Two rejection cases contain pure noise and intact sync symbols with half the
data symbols spliced from a different desktop codeword.

`scripts/wsjtcb_recordings.py` regenerates these derivatives byte for byte, then
checks native payload/text metadata against the original WSJT-CB LDPC/CRC and
unpacker adapter. Both decoders must reject the negative cases. The four AWGN
SNR estimates must be within 3 dB of the injected level. This is a small fixed
corpus, not a sensitivity or false-positive rate measurement. The reference
adapter is supplied the known onset and 1000 Hz base; it does not run
the complete desktop candidate search or acceptance filters.

```bash
source scripts/env.sh
bash scripts/controlled.sh 600 bash scripts/check.sh
# Requires the reference compiler/Boost setup described in FT8_ENGINE.md.
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_recordings.py .tooling/native-host
```

The separate phone probe reads the same recordings through the production JVM
WAV reader and recorded-slot decoder, checking payloads, cold text, onset/offset,
SNR tolerance and rejection. The A51 probe and UI checks passed for PR #23,
merged after owner approval on 2026-11-06. [Validation](VALIDATION.md#latest-samsung-a51-result-r08) records the
exact commit/run, per-case times and checked UI paths. CI alone does not establish
an Android device pass.

## Phone review

These are repeatable review steps; use [validation](VALIDATION.md) to distinguish
completed checks from additional tests.

The committed clean CQ file is [`channel/cq.wav`](../reference/wsjtcb/channel/cq.wav);
noisy/corrupt/noise fixtures and their hashes are in the
[recording corpus](../reference/wsjtcb/recordings/recordings.json). Copy test WAVs
into a dedicated Download folder, leaving existing documents alone. Some Android
file pickers need media indexing before they display files copied through ADB;
refresh/index only the test files, or select them through internal storage.

1. Let the agent deploy the CI APK and copy test WAVs to the authorized debug
   phone. Unlock it when notified and keep it awake for the UI checks.
2. Open **Audio**, enter the UTC boundary above, then **Open WAV and decode**.
   Select `cq.wav`: expect `CQ 1AT106`, approximately 1000 Hz and DT +0.30 s.
3. Import `noisy-cq.wav`: expect the same payload/text and estimated SNR near -4 dB.
   Directed fixtures may show the saved station in place of a cold `<...>` hash.
4. Import `corrupt-data.wav` and `noise-only.wav`: expect no valid frames.
5. Try invalid UTC, a short WAV and a non-12 kHz WAV; require explicit errors.
   Cancel the picker, reopen, rotate, clear results and retry an import.
   Verify station settings persist and recorded data is separate from LAN data.

# Foreground acoustic AutoSeq

Auto CQ and Auto answer execute the qualified CB QSO decisions through the phone
microphone and speaker. They require an applied Phone input/output profile,
saved station, valid NTP/GPS time, selected/calibrated speaker and active local
FT8 RX. LAN and imported recordings never drive this execution.

On Operate, select and calibrate the speaker, start RX, then explicitly press
**Auto CQ**. It calls on the chosen period and follows the first qualified
addressed reply. To answer, select a complete decoded sender with **Use peer**,
review offset/period, then press **Auto answer**. Advice alone never starts
AutoSeq. Reports use the local SNR estimate until sent, then remain fixed.

AutoSeq sends at most three copies of the same message, stops after two minutes
without protocol progress, and has a four-minute session limit. Completed
exchanges stop; another CQ requires an explicit new start. A lost message can
lead to a bounded repeat; duplicate or regressive messages do not invent
progress. Unknown hashes, unrelated finals and invalid messages retain the
[admission rules](QSO_ADVICE.md). Actual completed output confirms sent facts.

**Stop**, **Stop AutoSeq**, edits, a peer/period/source/profile change,
navigation, backgrounding, recreation, clock/latency failure, or an audio error
cancel automatic work. An interrupted waveform is not added to Sent. Returning
to the page, regaining focus, refreshing time or recreating the process never
arms or restarts a transmission. Keep Operate visible; this is not a background
operating service. Recalibrate when its five-minute latency measurement expires.

## Immediate response timing

Automatic reception decodes exactly 162000 genuine samples: UTC boundary through
+13.5 seconds at 12 kHz. The decoder consumes that length directly; it does not
pad an unrecorded tail. Gaps and initial partial windows are discarded. The
current automatic receiver keeps this timing after the exchange ends; a fresh
explicit Start RX selects ordinary 15-second reception again. Exported slots and
WAV import retain their 15-second contract.
Late signals outside the early observation can be missed. Late decoding never
causes a stale or shifted transmission: the fixed response deadline stops AutoSeq.

The generated slot remains 180000 samples. Automatic playback verifies its
leading/trailing silence, plays all 161280 frames through the complete 79-tone
waveform, and omits only the final verified zero padding. Sent and playback
reports record **161280 actual played frames**, not a claimed 15-second output.
Manual one-shot output still plays all 180000 frames. Audio remains exclusive:
the same coordinator releases output, waits the unchanged 500 ms echo guard,
then reopens the microphone with the existing timestamp-settling checks.

The early RX result supplies the next message before the following boundary.
The outgoing plan fixes that UTC boundary and requires preparation time before
its compensated command deadline. Device timing must pass the physical checks;
passing an offline decoder probe alone does not establish immediate RX recovery.

## Time renewal

After an initial explicitly applied NTP measurement, the selected server is
measured nominally every five minutes while the app is visible. No per-message
request or automatic server failover is used. A fresh measurement waits until
input is released and no output plan exists. Queued/playing plans retain their
immutable clock mapping. A periodic correction above 50 ms requires explicit
review/application; it cannot silently move an active exchange.

A small accessible warning on Operate links to Time synchronization when a
refresh fails or time expires. Failure preserves the previous measurement's
original ten-minute expiry; it does not extend it. Expiry disables TX. A later
successful renewal can restore time while idle but cannot restart RX or AutoSeq.
GPS remains explicit, with no hidden NTP dependency. See [time synchronization](UTC_RECEIVE_SLOTS.md).

## Reproduce checks

Run the normal JVM/lint/debug checks and native controls. With matching CI APKs,
the offline probe also reports `early_receive_windows=20` for original desktop
and generated inputs. Those checks do not exercise audio devices.

For physical acceptance, install the same run's app, device-check and engine-probe
APKs, save station `1AT106` with a valid locator, apply a Phone profile and select
a working NTP service. Place an unlocked phone near physical PC speakers and
microphone. Use a synchronized Linux host with reference compilers, NumPy/SciPy,
PipeWire tools and an installed **WSJT-CB** `jt9` decoder:

```bash
source scripts/env.sh
timeout 1200 python3 scripts/autoseq_audio_checks.py \
  --output /tmp/8cbdroid-autoseq --jt9 /path/to/wsjt-cb/jt9
```

The test uses actual Operate buttons, five calling outputs with an intentionally
lost reply and repeated reply, three answering outputs and three no-response
CQs. All expected peer responses use the immediately following RX period; a
whole-cycle delay fails acceptance. It observes a real five-minute NTP renewal,
then checks queued/playing Stop, edits, clock reset, focus, navigation and retained
recreation. The PC decodes each actual phone waveform with installed WSJT-CB
before supplying its response, using a genuine 15-second observation ending at
slot +13.4 seconds, with desktop depth 1 for the live response decision. All eleven
recordings must also pass depth-2 desktop candidate search,
independent original packing/LDPC/CRC and all 79 tones, plus measured onset and
timestamp-anchor checks. Keep reports and ambient recordings private. Failed or
partial attempts do not constitute a full pass. An analysis-only replay uses
`--analyze-only` with the same output and decoder.

The test requires USB ADB. Its final time scenario saves/disables phone Wi-Fi
and mobile data, observes a real periodic failure and the warning link, restores
the original network states and respects the server request interval before an
explicit NTP retry/application. No network or debug-phone settings are saved in
the public source tree. Time recovery must leave RX/TX and AutoSeq stopped.

The relevant PR records tested commit, CI, device, results and limitations.
This is acoustic software interoperability and inferred protocol completion;
it does not establish radio TX, an RF QSO, USB/CAT operation or a durable log.

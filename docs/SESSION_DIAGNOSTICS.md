# Session diagnostics

Open **Settings → Session diagnostics → Start diagnostics** to opt in. This does
not start reception, arm TX, request a permission or change the clock. Start the
wanted receiver separately. Collection belongs to the process-level graph and
continues across screens and Activity recreation while Android lets the process
run. It adds no foreground service or wake lock; an active receive session retains
its existing ownership. Background scheduling without RX is not guaranteed.

One report is retained in private, backup-excluded storage, with atomic checkpoints
every 30 seconds. Collection ends after one elapsed hour, explicit Stop, deletion,
a checkpoint error or process termination. Reopening an unfinished checkpoint marks
it interrupted; collection, RX and TX never restart automatically. Up to 122 resource
samples and fixed aggregate counters are kept, bounded to 256 KiB on disk. Delete
the existing report before starting another.

## Contents and limits

Reports contain app version, phone model, Android version, relative elapsed times,
receiver/source phases, decoder profile, actual input rate, clipping and drift
observations, measured-time revision/quality, TX activity, resource samples and
aggregate local-window/decode counters. They contain no audio, message text,
callsigns, locator, GPS coordinates, device serial, network addresses or server
names. No telemetry is uploaded. Existing explicit WAV tools are separate.

Resource observations include process PSS, cumulative process CPU milliseconds,
battery level/temperature, external-power state and Android thermal status where
available. Unsupported measurements are null. Battery temperature is not processor
temperature. Charging tests cannot measure battery drain; short tests and battery
percentage changes do not establish operating life. CPU/PSS include the app's other
work and should be compared over similar intervals and workloads.

A complete audio window is not necessarily a decoded message. Empty windows can
decode successfully. Failed decode attempts and incomplete windows are separate;
pending windows discarded due to overload **or cancellation** share an explicitly
named counter. Decode time uses backend-reported successful decode durations.
Counters aggregate across RX runs, including time-refresh recovery. LAN rows are
sampled retained history size, capped by the existing LAN history; they are not a
total packet/message count and do not measure local DSP latency.

## Export and deletion

**Export diagnostic report** uses Android's document picker for a JSON snapshot.
Choosing a destination exports the snapshot taken when Export was pressed; a later
checkpoint cannot change that snapshot. Cancel leaves the local report intact.
Provider errors remain visible and allow another destination/retry. A running
export finishes before deletion is available. Exported files are controlled by
the selected provider and are not erased by deleting this app's report.

**Delete diagnostic report** asks for confirmation, stops collection, joins any
checkpoint writer, invalidates pending document selection and deletes the local
checkpoint. It does not stop RX/TX, erase contacts or change settings. A late picker
callback or finishing writer cannot recreate a deleted report.

## Sustained-operation qualification

[PR #45](https://github.com/vash909/8CBdroid/pull/45) completed the finite A51 /
Android 13 and S25 FE / Android 17 checks. [Recorded measurements](VALIDATION.md#continuous-sessions-and-diagnostics)
include actual 15-minute microphone reception and two NTP renewals per phone.
The procedure below reproduces that scope; it does not claim battery life or
all-device qualification.

Use successful-CI app/device-check APKs on the baseline and a second phone. Save
original settings/data and grant only the ordinary receiver permissions. Check the
actual UI's opt-in, navigation/recreation, Stop, export cancellation, real document
provider failure/retry, interrupted-checkpoint reopening and confirmed deletion.
Use JVM tests for one-hour limits and writer/delete races; do not fabricate phone
measurements to claim elapsed-hour, battery or thermal evidence.

For each phone, collect at least 15 minutes of actual UTC microphone FT8 reception,
including navigation, background/screen-off, at least two periodic NTP boundaries,
and explicit receiver/diagnostic Stop. Use audible desktop reference signals for
repeatable successful decodes, or real radio-speaker FT8 as field evidence. Track
receiver errors, window/decode counts, time revisions and resource samples. Exercise
LAN separately with actual UDP traffic and verify the report identifies its source
without retaining addresses or payloads. Record exact duration/device/build and any
failures; the minimum run is a baseline, not an unlimited-runtime guarantee.

A radio-speaker trial is useful field evidence. Decode-loss or sensitivity comparisons
require an independent decoder observing the same input; differing acoustic paths
cannot establish parity or identify every missed transmission. No RF transmission
or radio-keying result follows from receiving audio. USB/CAT diagnostics need their
own hardware qualification when those backends are available.

Published qualification belongs in the feature PR and [validation record](VALIDATION.md).
Keep raw reports, ambient audio, settings backups and device identifiers private.

With matching app/test APKs installed and one selected authorized phone:

```bash
source scripts/env.sh
python3 scripts/session_diagnostics_checks.py --mode smoke --output /private/new-ui-check
python3 scripts/session_diagnostics_checks.py --mode interrupted --output /private/new-restart-check
python3 scripts/session_diagnostics_checks.py --mode sustained --output /private/new-audio-check
python3 scripts/session_diagnostics_checks.py --mode lan --output /private/new-lan-check
```

Select a device through `ANDROID_SERIAL` if more than one is connected. Sustained
reference playback needs the existing desktop reference compiler environment,
physical PC speakers near the phone, audible levels and synchronized host UTC.
Each acoustic session is 15 minutes with a finite 1,050-second host deadline;
LAN runs for 90 seconds. The host supervises the process and prints progress without
continuous device polling. Use `--radio` with `--mode sustained` for field reception;
at least one actual FT8 slot must decode, compared with eight for known reference
playback. Field traffic does not provide a controlled sensitivity benchmark.
The last acoustic phase turns the screen off and can leave it locked. The suites
start only with no existing report and delete only their own bench report. The
interrupted pair retains its first checkpoint solely for the matching cold check.
Review saved failure evidence before retrying, and keep all output private.

# Manual acoustic operation

**Operate** sends one FT8 message at a time through the built-in phone speaker
and receives through the built-in microphone. A radio can use this acoustic
path with its own VOX settings; set its dial, sideband, audio levels and VOX
manually. There is no CAT/PTT feedback, AutoSeq or QSO log. LAN remains read-only.

## Prepare

1. Save a complete CB callsign and locator in **Settings → Station**. Apply a
   profile with **Phone** input and output, and select the built-in microphone.
   The profile's reference dial is entered configuration, not radio readback.
2. Measure and apply NTP or GPS time in **Time synchronization**. Return to
   **Operate**, select **Phone speaker** and press **Calibrate speaker**.
   Silent calibration expires after five minutes; recalibrate when necessary.
3. Press **Start RX** and grant microphone permission. Live FT8 reception must
   be active before enabling or sending a message. Plain Monitor is insufficient.
4. Set **TX Hz**, audio level and Even (00/30) or Odd (15/45). The TX cursor also
   selects the output frequency. Output accepts 200–2900 Hz; the RX/display
   cursor extends to 2950 Hz. These controls do not tune or filter the radio.
5. **Test tone · 2 s** explicitly plays a two-second tone with short ramps and
   100 ms silence at both ends. It disarms TX, pauses reception and never adds
   an FT8 message to Sent. Audio level is a digital amplitude, not calibrated RF power.

## Exchange messages

**Use peer** appears only for an integrity-checked local message with a complete,
unambiguous CB sender different from your own station. It suggests a reply,
opposite UTC period, TX audio frequency and editable estimated SNR report.
Selection never enables TX. **→ RX** changes only the RX cursor. You can instead
enter a complete **CB peer** manually; unknown hashes cannot supply an identity.

Choose CQ, Reply, Report, R-report, RR73 or 73. Templates follow the pinned
WSJT-CB FT8/NONE rules, including its one-callsign reports and hash orientations
for long CB calls. For example, with `1AT106` and peer `26AT101`, Reply is
`<26AT101> 1AT106`, Report is `26AT101 -07`, and R-report is `26AT101 R-07`.
The report field is an editable estimate, not a calibrated measurement.

Editing **TX message** creates a custom message. Inspect **Encoded** before
sending. Select free-text mode explicitly when wanted; its limit is 13 encoded
characters. Lossy content changes require consent, which any edit revokes.
Unsupported input, unresolved placeholders and incomplete CB identities block TX.

Press **Enable TX**, then **Send one slot**. Enabling alone produces no audio.
The message, payload, peer, level, output, period and time mapping are frozen for
one slot with at least two seconds of preparation margin. A changed correction
cancels rather than moving the transmission. The microphone and decoder are
stopped and joined for the entire wait and output; no self-decoding occurs.

After successful playback, audio ownership is released, a 500 ms echo guard
passes, and the previously active built-in receiver is reopened if its setup is
still valid. Old samples, results and learned hashes are cleared; the first
partial receive slot is discarded. Choose and enable the next message manually.
There is no automatic repeat, answer, retry, final-message action or QSO completion.

**Sent** retains up to 30 completed speaker slots in the current process, with
slot UTC and text. It confirms complete audio playback only: it cannot confirm
radio keying, RF output, receipt by another station or a completed QSO. It is not
a persistent log. Interrupted slots are omitted; audio already emitted before
Stop cannot be recalled.

## Stop and recovery

**Stop** cancels queued/playing audio, disarms TX and stops reception. Message,
peer, report, period, frequency, level or output edits also cancel/disarm.
Navigation away from Operate, backgrounding, rotation, source/profile changes,
audio-focus loss, invalid/expired time or calibration, and route changes require
explicit recovery. Output errors never restart RX. Check the displayed error,
start RX and enable/send again; nothing resumes silently.

Applying a profile or changing operating setup clears speaker selection and
calibration. Starting a new process restores neither pending work nor armed TX;
station/profile/server preferences remain saved. Manual output stops when
leaving Operate. An active RX may continue into the detailed microphone monitor;
configuration, Chat, background and rotation stop local capture. Continuous screen-off/background operation is not implemented.

## Reference and physical checks

`python3 scripts/manual_messages_reference.py --check` verifies 61 template/text/
payload expectations extracted from the independently generated pinned desktop
QSO corpus. JVM tests cover CB peer selection, encoding consent, frozen one-shot
work, cancellation, expiry, failures and fresh-process state.

For an acoustic bench, save station `1AT106` with a valid locator and apply a
Phone input/output profile. Install matching app/test/probe APKs, grant microphone
permission and leave the phone unlocked near physical PC speakers and microphone.
Use the desktop compiler setup in [development](DEVELOPMENT.md), then run:

```bash
source scripts/env.sh
python3 scripts/phone_audio_checks.py --manual --output /path/to/new-private-directory
```

`--ntp-host HOST --ntp-port PORT` selects a real nearby NTP reference. The host
generates incoming WAVs using pinned original WSJT-CB channel routines, plays
them acoustically, and records three outgoing phone messages with physical
microphone timestamps. The phone checks actual Operate controls, manual peer/
message selection, RX/output recovery, cold/warm receive context, bounded tone,
Stop, edits, focus loss, time reset, navigation, recreation and profile cleanup.
Independently decode all recorded outgoing payloads/tones and measure onset;
Android playback counters alone are insufficient. These are acoustic software
checks, not radio/RF observations or the complete desktop GUI/search pipeline.
Keep recordings and settings backups private. See [validation](VALIDATION.md)
for observed results and [UTC scheduling](UTC_AUDIO_SCHEDULING.md) for timing limits.

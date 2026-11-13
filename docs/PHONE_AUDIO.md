# Coordinated phone audio bench

**Settings → Message tools** combines built-in microphone reception with the
existing explicit speaker preview and one-shot UTC scheduler. Keep radios
disconnected: this qualifies phone audio handling, not radio VOX, CAT/PTT or an
RF QSO. [Manual operation](MANUAL_OPERATION.md) uses the same coordinator from Operate
with explicit one-message enable/send and recovery of the previously active RX.

1. Apply an NTP/GPS measurement in **Time synchronization** for FT8 reception
   and scheduled output. Plain **Monitor microphone** needs no time correction.
2. Open **Message tools**, select a built-in microphone and press **Receive FT8**
   or **Monitor microphone**. Grant microphone permission if requested.
3. Generate an encodable message, explicitly select **Phone speaker**, and
   choose the output level. Generation alone never starts audio.
4. Optionally check **Resume microphone after successful output**. This is
   process-local consent; it is off initially and is cleared by applying a profile.
5. Play a preview, measure silent speaker latency, or queue one even/odd slot
   after measuring latency. The microphone and decoder are stopped and joined
   before output owns audio, including the entire queued wait.
6. Successful output releases AudioTrack/focus, waits 500 ms for the output tail,
   then attempts to resume the previously active built-in microphone if consent
   was given. It never starts an input that was idle or waiting for permission.

The resumed receiver keeps its input, station, UTC/live options and decoder
profile, but clears old audio, local results and learned hashes. Initial partial
UTC windows are discarded; only new complete slots may decode. The output
waveform itself is never offered to the receive decoder. Room echo longer than
the fixed guard is not characterized; this is not acoustic echo cancellation.

## Stop, failures and lifetime

- **Stop preview**, waveform/output/period edits, errors, audio-focus loss,
  navigation, background and rotation cancel output without resuming input.
- **Stop microphone** during output revokes receive recovery while allowing the
  explicitly requested output to continue. Use **Stop preview** to halt output.
- A removed/changed input inventory, changed station, time correction/expiry,
  profile application or revoked permission prevents stale recovery. There is
  no automatic route substitution, permission prompt or retry during recovery.
- Manual microphone starts/configuration are blocked while output/recovery owns
  audio. Cancellation keeps that ownership until hardware cleanup returns.
- Receive reopening can fail normally, for example because Android denies the
  recorder. The capture error is shown and an explicit retry is required.
- Leaving Message tools cancels output. An idle receiver continues through
  navigation/background/rotation via the [receive service](CONTINUOUS_RECEPTION.md);
  cancelling output still does not resume a paused input. TX remains foreground-only
  and process death never restores either path.

`PhoneAudioCoordinator` is a JVM contract owner; Android recorder/output I/O
stays behind the existing audio interfaces. The recovery attempt is one-use and
bound to unchanged input/time/station settings. The 15-second input-cleanup
deadline and existing output/clock/deadline guards remain in force. See
[UTC scheduling](UTC_AUDIO_SCHEDULING.md) for timing and calibration limits.

## Verify

JVM checks cover exclusive ownership, actual input-cleanup ordering, preparation
timeouts, cancellation during recovery, permission/route/station/time changes,
one-use recovery and no recovery for an idle/non-phone input.

With matching app/test/probe APKs, microphone permission granted, an unlocked
phone close to the PC's physical speakers and microphone, and an NTP-synchronized
Linux host with PipeWire and libpulse-simple:

```bash
python3 scripts/phone_audio_checks.py --output /path/to/new-private-directory
```

Use `--ntp-host HOST --ntp-port PORT` for a real nearby NTP reference. The test
plays pinned desktop WAVs through PC speakers, observes real phone decodes before
and after two even/odd output/recovery cycles, and records the phone speaker.
It also exercises Stop, actual focus loss, correction reset, edits, navigation,
retained recreation and profile cleanup. Run IDs reject stale acknowledgements.
Independently decode both captured outputs and measure their onset; Android
frame counts alone are insufficient. These checks observe phone audio; they do not establish radio keying or RF output.


The automatic path uses this same exclusive owner and unchanged 500 ms echo
guard. It plays the entire generated signal, omitting only verified zero tail
padding and reporting its actual 161280 frames; manual/bench output remains
180000 frames. A staged periodic NTP refresh can be adopted while input is
released and before any output plan exists; only that validated immediate
periodic successor can preserve a recovery request. Explicit reset/source
changes still invalidate it. See [AutoSeq timing](AUTOSEQ.md#immediate-response-timing).

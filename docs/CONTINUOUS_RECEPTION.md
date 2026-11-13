# Continuous operating sessions

An explicit **Start RX**, **Monitor** or **Start microphone** request starts one
receive foreground service. The same microphone or WSJT-CB LAN listener continues
through Settings/Chat navigation, Activity recreation, switching apps and screen-off.
Returning observes that session; it does not open another input or socket.

## Controls and permissions

Allow microphone access for local audio and **receive notifications** when asked.
Notifications must remain enabled so **Stop RX** stays available outside the app.
The ongoing notification identifies the phone audio or WSJT-CB LAN session and
opens the app. During TX/AutoSeq it shows **Stop TX** and **Stop session**;
with RX alone, **Stop RX** ends reception. Its session Stop and the in-app controls invalidate pending starts and
recovery, cancel output, join recorder/socket cleanup and remove the notification.
A normal microphone Stop retains recent diagnostic PCM/the last full slot for
explicit WAV export; live rows/hash learning are cleared. Profile changes and
errors discard transient audio. No recording is automatically written to disk.

Start from the visible app. Missing permission, a blocked notification channel,
Android service-start rejection, input/route loss and silencing produce an error;
restore the prerequisite and start again explicitly. There is no boot receiver,
sticky-service restart or persisted active-session flag. Process death/force-stop
ends RX and never restores RX, TX or AutoSeq automatically. Removing the task does
not itself request Stop; use the notification or Android's active-app controls.

Opening settings changes no input. Source changes and explicit profile application
stop the old session first. Input/decoder controls remain locked while capturing;
Stop before changing them. Station/time changes invalidate affected receive state.
Microphone, imported WAV and desktop LAN decoding retain separate provenance and
hash sessions. The existing single decoder/pending-slot and bounded histories apply.

## Time, power and transmit boundary

Local FT8 requires measured NTP/GPS time; Monitor does not. The existing periodic
NTP policy continues during active background RX. A pending measurement is applied
only after joined input cleanup at a receive-only boundary; partial slots and hash
context are discarded, then that same authorized receive session opens a fresh run.
An expired/unusable clock stops timed capture. GPS remains an explicit measurement;
there is no silent source switch or manual offset.

The service holds a partial CPU wake lock while active, and a Wi-Fi lock for LAN.
It never keeps the display lit or changes Wi-Fi networks, battery settings or lock
credentials. Stop/failure/service destruction releases its resources. Continuous
capture/decoding uses battery; Samsung or other vendor power policy can still end
the process. No unlimited-runtime or all-Android-version qualification is implied.

**Explicit operating output also continues.** After starting local FT8 RX, select
and calibrate the speaker, then explicitly enable one manual message or start
Auto CQ/answer. The same session retains output, timing, QSO tracking and log
completion through navigation, Activity recreation, background and screen-off.
Returning observes the same owner; it never arms an idle or restarted process.

**Stop TX** cancels queued/playing output and AutoSeq. A still-running receiver
can remain active; a receiver paused by cancelled output does not silently reopen.
**Stop session** ends both RX and TX. Source/profile/station changes, invalid time,
permission loss, audio-focus/route errors and service destruction invalidate work.
Restoring a prerequisite never restarts TX. Completed audio alone is added to Sent.

An unused manual TX arm expires after two minutes. Each audio operation, including
preparation and cleanup, has a 60-second coroutine deadline. AutoSeq retains its
three-copy, two-minute no-progress and four-minute session limits, with an
additional monotonic four-minute deadline that includes preparation. These bounds
never reschedule late audio or bypass UTC, calibration or permission checks.
Standalone calibration/test tone without RX and Message tools previews remain
foreground-only. An explicit bench output cancels competing operating output
before acquiring the shared audio coordinator. CAT/USB hardware remains separate.

The service uses Android's microphone type for local input and connected-device
type for the network listener. Before authorizing continuous speaker output, the
existing microphone service adds the mediaPlayback type; failed promotion cannot
grant TX authorization. No second audio service is created. Start and notification behavior follows the platform
[service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[foreground launch restrictions](https://developer.android.com/develop/background-work/services/fgs/launch)
and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
Background playback retains the existing [audio-focus checks](https://developer.android.com/media/optimize/audio-focus).

## Reproduce phone checks

Use matching app/device-check APKs, an authorized unlocked phone with saved Phone
profile, station `1AT106`, working NTP, notification/microphone permission and the
same LAN as the PC. Keep physical PC speakers audible near the phone. With the
reference compiler environment and synchronized PC time:

```bash
timeout 960 python3 scripts/receive_session_checks.py --output /private/path/receive-check
```

This plays three original-desktop FT8 files through the PC speakers during actual
navigation, background and screen-off. It verifies decoded complete slots, the
same capture/run across navigation/rotation/new Activity, one real recorder/service,
notification/in-app Stop, actual microphone app-op interruption/restoration with
explicit retry, real desktop-simulated UDP traffic across the same transitions, and an actual
five-minute NTP refresh with receive recovery while the screen remains off.
The script temporarily denies the already-authorized microphone app-op for that
failure case and restores its original mode even on failure. The phone may require
one unlock between screen-off checks; the host prints an action marker and the
phone waits up to three minutes. It leaves RX stopped and may leave the screen locked.
No TX/radio command is sent. Keep reports/device identifiers/recordings private.

After host force-stop, run the `receive-cold` device-check scenario with a unique
`preview_run` to verify a new process has no active receiver, service or armed TX.
Real USB detach qualification requires the separately supported physical USB route;
this change does not qualify USB audio. Exact tested device/OS and CI revision
belong in the feature PR and [validation record](VALIDATION.md).

## Reproduce continuous TX and AutoSeq

Use matching successful-CI app/test/probe APKs, test station `1AT106`, a Phone
profile, a working selected NTP server, a synchronized Linux host and physical
PC speakers/microphone. See [manual operation](MANUAL_OPERATION.md#reference-and-physical-checks)
for the manual acoustic suite. It records three full output waveforms, including
background/Activity destruction and real rotation, checks explicit UI/notification
Stop and a real two-minute unused-arm timeout, then removes only its staged log
fixtures by ID. Independently decode the physical recordings and measure onset.

For full automatic calling, bounded no-response and a final complete screen-off
answering exchange, use the original installed WSJT-CB decoder:

```bash
source scripts/env.sh
timeout 1500 python3 scripts/autoseq_audio_checks.py --continuous \
  --output /path/to/new-private-directory --jt9 /path/to/wsjt-cb/jt9
```

This checks queued/playing Stop, edits, clock reset, real focus and microphone
access loss, profile replacement, navigation/Activity recreation and notification
Stop before the acoustic exchanges. It verifies the actual microphone/mediaPlayback
foreground type, live desktop decoding of all eleven physical phone outputs,
complete peer responses, receive recovery and a real periodic NTP update. The
last answering exchange runs with the screen off and needs no UI intervention.
Completed bench exchanges must stage exactly one log review each; fixtures are
removed by ID while existing contacts/pending entries are preserved.

This mode omits the separate NTP outage/UI-recovery scenario; the default
[AutoSeq suite](AUTOSEQ.md#reproduce-checks) retains it. Follow with force-stop and
`receive-cold` as above to check that a new process leaves RX/TX/AutoSeq off.
Keep ambient recordings, device identifiers and settings backups private. Actual
Android device/CI results belong in the feature PR; these steps alone are not a
qualification claim for every phone or Android version.

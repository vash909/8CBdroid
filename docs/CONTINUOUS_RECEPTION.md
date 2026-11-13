# Continuous reception

An explicit **Start RX**, **Monitor** or **Start microphone** request starts one
receive foreground service. The same microphone or WSJT-CB LAN listener continues
through Settings/Chat navigation, Activity recreation, switching apps and screen-off.
Returning observes that session; it does not open another input or socket.

## Controls and permissions

Allow microphone access for local audio and **receive notifications** when asked.
Notifications must remain enabled so **Stop RX** stays available outside the app.
The ongoing notification identifies Microphone RX or WSJT-CB LAN RX and opens the
app. Its Stop action and the in-app Stop controls invalidate pending starts and
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

**Transmit remains foreground-only.** Leaving Operate, backgrounding or rotating
cancels/disarms queued speaker output and acoustic AutoSeq. Successful foreground
speaker output may recover an existing input through its qualified coordinator;
cancelled output cannot silently restart it. Receive continuity does not authorize
background transmission. CAT/USB hardware and full TX session ownership are separate.

The service uses Android's microphone type for local input and connected-device
type for the network listener. Start and notification behavior follows the platform
[service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[foreground launch restrictions](https://developer.android.com/develop/background-work/services/fgs/launch)
and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

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

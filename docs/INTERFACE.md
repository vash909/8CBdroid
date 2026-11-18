# Interface and navigation

8CBdroid opens on **Operate**. The bottom navigation has three destinations:
Operate, Settings and Chat. App text remains English regardless of Android's
system language. No connection or microphone session starts on launch.

## Operate

- The applied operating profile, station identity and reference dial appear at the top.
  The saved local reference defaults to **27.265 MHz**; it is not a measured radio frequency. With LAN selected,
  the dial comes from desktop status and is explicitly read-only.
- The compact local spectrum/waterfall uses the existing 2 Hz analyzer and
  96-row history. **Expand ↗** opens the full monitor. LAN supplies no waterfall
  audio, so that source shows an explanation rather than invented samples.
- **Received** shows the selected source, with estimated local SNR and desktop
  confidence/replay flags. The message details' **→ RX** changes the RX cursor; **Use peer** selects a
  complete local CB sender and suggests a reply, report, opposite period and TX
  frequency without arming. **Sent** records only completed speaker audio.
- Message rows use compact UTC/dB/Hz/text columns. Tap a row for its full text,
  source/confidence flags and country context. Local-message details retain
  **→ RX** and **Use peer**; both are also accessibility actions. Long text is
  available in details even when the narrow row is ellipsized. Local history
  retains the latest 300 rows; LAN retains 200 distinct decodes.
- Hold a local received station row to select its decoded sender, report,
  frequency and opposite period and explicitly start **Auto answer**, including
  automatic TX. A short tap still only opens details. The gesture requires a
  fresh, integrity-checked decode from the active local receiver and all normal
  time/speaker/RX interlocks. LAN, Sent, unknown identities and old history cannot
  start an answer; a running exchange is not replaced by another hold.
- A small bottom-right bar follows each UTC-aligned 15-second FT8 period, showing
  Even/Odd and elapsed seconds. It stays visible while Operate scrolls. Applied
  NTP/GPS correction drives it when valid; otherwise it explicitly shows
  **Unsynced** using device UTC. The display never authorizes TX or measures time.
- Received **All / CQ / To me** filters change the display only. Identified senders
  show their CB division/country; an unknown sender has no label. **Worked** shows
  senders in the current station's saved log. A selected local peer also shows country.
  See [CB countries and filters](CB_OPERATING_CONTEXT.md) for provenance and limits.
- **Logbook** opens durable completion reviews and saved contacts. Review and
  confirm the radio dial before logging; manual entries, editing and search are
  available, along with [ADIF exchange and backups](ADIF.md). Opening the logbook
  retains an already started operating session; use its explicit Stop controls.
  See [the logbook guide](LOGBOOK.md).
- **RX Hz** controls the 200–2950 Hz display cursor and highlights local received
  rows within ±25 Hz; it does not hide other stations. **TX Hz** also selects actual
  manual output within 200–2900 Hz. Neither tunes a radio or filters decoding.
  LAN offsets are unavailable; the separate offline waveform editor is unchanged.
- **Auto CQ** shows a compact **CQ n/20** counter and stops after twenty calls
  without a peer. Local SNR values no longer carry an asterisk; their details
  still identify the estimate and source.
- **Start RX** starts local synchronized FT8 reception or the desktop listener.
  **Monitor** starts plain local audio without time sync. **Stop** stops RX and
  cancels/disarms manual output.
- **Enable TX** followed by **Send one slot** sends one manually selected message
  with measured time/calibration and active built-in FT8 RX. **Auto CQ** and
  **Auto answer** explicitly start bounded operating sessions; see [AutoSeq](AUTOSEQ.md).
  CQ/reply/report/final templates, custom/free text, encoding consent,
  Even/Odd, speaker selection, level, silent calibration and a two-second test
  tone are below the main controls. See [manual operation](MANUAL_OPERATION.md).
  Radio/VOX setup is manual; Sent is not a QSO log or radio TX confirmation.

The message list scrolls independently. On short windows, including an open
keyboard, the operating page scrolls so its controls remain reachable.

## Settings

| Page | Contents |
| --- | --- |
| Operating profiles | Named radio/audio/LAN setups, save/apply, validation, discard/delete and disconnected startup. See [profiles](OPERATING_PROFILES.md). |
| Logbook | Persistent pending reviews, saved/manual/imported contacts, edit/search, ADIF exchange and full-log backup/restore. |
| Station | Persistent callsign and Maidenhead locator. |
| Audio & source | Local audio versus LAN, actual Android input devices and Fast/Standard profile. USB inputs appear when detected; manual output selects the phone speaker on Operate. |
| Time synchronization | Regional/custom NTP server, NTP-first/GPS-first automatic startup and ten-minute retries, manual measurements/application and holdover status. |
| WSJT-CB LAN | Desktop filter, UDP port, status, Replay and detailed decode history. |
| USB / CAT | Explicit Hamlib catalog/search/details and software-only Dummy check, plus descriptor/driver discovery and Android permissions; no hardware CAT commands. |
| Microphone tools | Full spectrum controls, input diagnostics, UTC options and recent/complete-slot WAV export. |
| Message tools | Codec previews, hash simulation, WAV generation/export and explicit bench speaker preview and one-shot UTC scheduling, plus built-in microphone reception with opt-in recovery after successful output. See [phone audio](PHONE_AUDIO.md). |
| Recorded audio | Existing WAV import and recorded-slot decoder. |
| Session diagnostics | Explicit bounded technical collection, private checkpoints, report export and confirmed deletion. |

Explicit microphone/LAN reception and explicitly started manual TX/AutoSeq
continue through Settings, Chat, rotation, Activity destruction, background and
screen-off. The notification and global controls show RX/TX/AutoSeq status:
**Stop TX** cancels output/automation; **Stop session** ends RX and TX together;
RX alone has **Stop RX**. Returning observes the same owner and never arms TX.
See [continuous sessions](CONTINUOUS_RECEPTION.md).
Message tools stops output when left; only successful explicitly opted-in output
may recover an existing microphone. Switching source or applying a profile stops
the current receiver; process death never rearms reception or transmission.

Station and NTP server preferences retain their existing persistence. Operating
profiles persist separately. Temporary source choices survive navigation/rotation
in the current process, including a new Activity; a new process restores the active saved profile. Manual drafts and completed-audio history are process-local. Applying a profile
clears output selection/calibration; no pending or armed TX is restored.

## Verify the operating layout

With matching debug app/device-check APKs, an unlocked phone and notification
permission, run:

```bash
timeout 180 adb shell am instrument -w -e scenario operate-layout -e preview_run layout-check-1 \
  net.vash.ft8cbdroid.test/net.vash.ft8cbdroid.validation.WaveformPhoneChecks
```

This checks real UTC ticks and rollover, 15 simultaneous on-device UDP decodes,
compact-row visibility, details/flags, filters, navigation, recreation and actual
rotation. A labeled clock fixture checks corrected display; it does not qualify
NTP/GPS acquisition. Local presentation fixtures check peer/RX actions, RX highlights,
the explicit hold callback, busy-state blocking and large text without starting
a microphone or output. They do not qualify actual AutoSeq audio. Saved settings are preserved;
reports/screenshots stay in private app cache. A UI test is not radio evidence.

## Chat

Chat is a placeholder. It opens no socket, creates no account, lists no invented
users and provides no send action. Its behavior will be defined separately.

## Visual references

The color palette takes inspiration from [XZ Group](https://xzgroup.net/) and
its [published colors](https://xzgroup.net/assets/css/site.css): charcoal
`#111311`, ivory `#E3E4DA`, ochre `#C9B76C` and olive `#A2B292`.
The activity/operating-control arrangement follows familiar FT8 workflows such as
[FT8CN's guide](https://github.com/N0BOY/FT8CN/blob/release/FT8CN%20Quick%20Guide%20v0.89.pdf).
No website assets or FT8CN UI code are bundled. Monospaced frequencies/messages,
compact labels and ordinary Android controls keep the operating view readable.

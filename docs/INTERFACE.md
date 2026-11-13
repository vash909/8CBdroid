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
  confidence/replay flags. **→ RX** changes the RX cursor; **Use peer** selects a
  complete local CB sender and suggests a reply, report, opposite period and TX
  frequency without arming. **Sent** records only completed speaker audio.
- Received **All / CQ / To me** filters change the display only. Identified senders
  show their CB division/country; an unknown sender has no label. **Worked** shows
  senders in the current station's saved log. A selected local peer also shows country.
  See [CB countries and filters](CB_OPERATING_CONTEXT.md) for provenance and limits.
- **Logbook** opens durable completion reviews and saved contacts. Review and
  confirm the radio dial before logging; manual entries, editing and search are
  available. Leaving Operate still stops/disarms audio. See [the logbook guide](LOGBOOK.md).
- **RX Hz** controls the 200–2950 Hz display cursor. **TX Hz** also selects actual
  manual output within 200–2900 Hz. Neither tunes a radio or filters decoding.
  LAN offsets are unavailable; the separate offline waveform editor is unchanged.
- **Start RX** starts local synchronized FT8 reception or the desktop listener.
  **Monitor** starts plain local audio without time sync. **Stop** stops RX and
  cancels/disarms manual output.
- **Enable TX** followed by **Send one slot** sends one manually selected message
  with measured time/calibration and active built-in FT8 RX. **Auto CQ** and
  **Auto answer** explicitly start bounded foreground exchanges; see [AutoSeq](AUTOSEQ.md).
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
| Logbook | Persistent pending exchanges, reviewed contacts, manual entry, edit/search and recovery. |
| Station | Persistent callsign and Maidenhead locator. |
| Audio & source | Local audio versus LAN, actual Android input devices and Fast/Standard profile. USB inputs appear when detected; manual output selects the phone speaker on Operate. |
| Time synchronization | Regional/custom NTP server, GPS measurement, explicit application and current correction. |
| WSJT-CB LAN | Desktop filter, UDP port, status, Replay and detailed decode history. |
| USB / CAT | Explicit Hamlib catalog/search/details and software-only Dummy check, plus descriptor/driver discovery and Android permissions; no hardware CAT commands. |
| Microphone tools | Full spectrum controls, input diagnostics, UTC options and recent/complete-slot WAV export. |
| Message tools | Codec previews, hash simulation, WAV generation/export and explicit bench speaker preview and one-shot UTC scheduling, plus built-in microphone reception with opt-in recovery after successful output. See [phone audio](PHONE_AUDIO.md). |
| Recorded audio | Existing WAV import and recorded-slot decoder. |

Leaving Operate cancels/disarms manual output. Moving to Microphone tools
preserves an already active receiver; entering configuration or Chat stops local
capture, and returning cannot restart it.
Message tools can explicitly start its own foreground microphone session; leaving
that page stops both input and output. Successful output may resume an already
active microphone only with the page's explicit recovery choice.
Switching source stops both receivers. Rotation/background still stop local
capture and discard transient audio/results. Chat also stops the LAN listener;
ordinary settings navigation preserves a foreground LAN connection.

Station and NTP server preferences retain their existing persistence. Operating
profiles persist separately. Temporary source choices survive navigation/rotation
in the current process; reopening restores the active saved profile. Manual drafts and completed-audio history are process-local. Applying a profile
clears output selection/calibration; no pending or armed TX is restored.

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

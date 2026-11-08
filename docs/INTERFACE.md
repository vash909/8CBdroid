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
- **Received** shows only the selected source: local microphone/USB input or
  WSJT-CB desktop messages. Local SNR has an estimated-value marker; desktop
  replay, off-air and low-confidence flags remain visible. **→ RX** selects a
  local decoded frequency, not a QSO peer. **Sent** is empty because no transmit
  backend exists.
- **RX Hz** and **TX Hz** edit the existing local display cursors within
  200–2950 Hz. Invalid drafts do not change the cursor. Unavailable LAN audio offsets show a dash. They do not tune a radio,
  filter decoder searches or configure the separate offline waveform editor.
- **Start RX** starts local FT8 reception with measured NTP/GPS time, or the
  desktop UDP listener for the LAN source. **Monitor** starts local visualization
  without time sync. **Stop** releases the selected receiver.
- **TX OFF** and disabled **Enable TX** accurately show the current boundary.
  Even/Odd and the custom-message field are UI drafts only: no message is queued,
  encoded, played or marked as sent. Unvalidated custom text is not a claim of
  FT8 encodability. Audio output, TX scheduling, CAT/PTT and AutoSeq remain absent.

The message list scrolls independently. On short windows, including an open
keyboard, the operating page scrolls so its controls remain reachable.

## Settings

| Page | Contents |
| --- | --- |
| Operating profiles | Named radio/audio/LAN setups, save/apply, validation, discard/delete and disconnected startup. See [profiles](OPERATING_PROFILES.md). |
| Station | Persistent callsign and Maidenhead locator. |
| Audio & source | Local audio versus LAN, actual Android input devices and Fast/Standard profile. USB inputs appear when detected; output selection remains unavailable. |
| Time synchronization | Regional/custom NTP server, GPS measurement, explicit application and current correction. |
| WSJT-CB LAN | Desktop filter, UDP port, status, Replay and detailed decode history. |
| USB / CAT | Explicit Hamlib catalog/search/details and software-only Dummy check, plus descriptor/driver discovery and Android permissions; no hardware CAT commands. |
| Microphone tools | Full spectrum controls, input diagnostics, UTC options and recent/complete-slot WAV export. |
| Message tools | Existing codec previews, hash simulation and offline WAV generation/export. |
| Recorded audio | Existing WAV import and recorded-slot decoder. |

Moving between Operate and Microphone tools preserves the same receiver.
Entering configuration or Chat stops local capture; returning cannot restart it.
Switching source stops both receivers. Rotation/background still stop local
capture and discard transient audio/results. Chat also stops the LAN listener;
ordinary settings navigation preserves a foreground LAN connection.

Station and NTP server preferences retain their existing persistence. Operating
profiles persist separately. Temporary source choices survive navigation/rotation
in the current process; reopening restores the active saved profile. Period and
custom-message choices remain UI drafts and can survive restored UI state.
No TX state is restored or armed.

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

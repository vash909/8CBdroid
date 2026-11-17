# 8CBdroid

An Android FT8 client in development for CB / 11 metres, built around
[WSJT-CB](https://github.com/vash909/WSJT-CB) message compatibility. The app and
project documentation use English.

**Current state:** phone-microphone FT8 reception, phone-speaker manual exchanges
and explicitly started acoustic AutoSeq are implemented. NTP/GPS time correction,
CB message generation, a 15-second period bar, compact message rows and a 2 Hz waterfall and continuous operating sessions
support the phone-audio path. Started RX/TX/AutoSeq sessions continue across
navigation, rotation, background and screen-off, with explicit Stop controls.

The durable [logbook](docs/LOGBOOK.md) supports completion reviews, manual and
imported contacts, editing, search and Worked filtering.
[ADIF import/export/share and full log backups](docs/ADIF.md) use Android's file
picker with explicit duplicate review. A separate LAN source monitors desktop
status and decodes. Named operating profiles and optional private diagnostic
reports are also available.

Radio dial, mode, levels and VOX are configured by the operator. CAT/PTT,
qualified USB audio and desktop LAN TX/log events remain unimplemented. USB
discovery and the Hamlib catalog/Dummy check do not establish hardware support.
Completed audio or a saved log entry does not independently confirm RF transmission.

## What works

| Screen | Available behavior | Current boundary |
| --- | --- | --- |
| **Operate** | Local or LAN activity, CB country labels, All/CQ/To-me/Worked filters, compact waterfall, local RX/TX offsets, peer/messages, manual one-slot sends and acoustic AutoSeq. | Sending requires measured time, speaker calibration and active built-in FT8 RX. LAN remains read-only and carries no audio. |
| **Logbook** | Durable pending reviews, saved/manual/imported FT8 contacts, edit/search, ADIF exchange and full saved/pending backup/restore. Accessible from Operate and Settings. | A completed local exchange needs operator review. Imports never overwrite existing entries; possible duplicates need explicit selection. |
| **Settings** | Station, operating profiles, source/decoder selection, NTP/GPS, LAN, USB inventory/Hamlib diagnostics, microphone/message/WAV tools and session diagnostics. | Radio and USB hardware backends remain unavailable. Recorded WAV import requires 15 seconds, mono PCM16, 12 kHz, at most 1 MiB. |
| **Chat** | Placeholder for future operator chat. | No chat backend, account or message sending. |

## Try the current app

Download **8CBdroid-debug** from a successful
[GitHub Actions run](https://github.com/vash909/8CBdroid/actions/workflows/android.yml)
and install its APK on a compatible Android phone. These are development builds.
See [deployment instructions](docs/DEVELOPMENT.md#deploy-and-test-on-a-phone) for
ADB commands and debug-signature limitations.

1. In **Settings → Operating profiles**, save/apply a Phone input/output setup.
   Applying stops the current session and clears time correction. In
   **Settings → Station**, save a complete CB callsign and a 4- or 6-character
   locator, for example `1AT106` / `JN45AB`. See [profiles](docs/OPERATING_PROFILES.md).
2. In **Settings → Time synchronization**, measure and apply regional/custom NTP
   (IP or hostname), or GPS with precise location permission. There is no manual
   offset. GPS timing was verified on the S25 FE; the A51 did not obtain a fix.
   See [time setup and limits](docs/UTC_RECEIVE_SLOTS.md).
3. Select the built-in microphone and **Fast** or **Standard** under
   **Settings → Audio & source**. On **Operate**, select **Phone speaker**,
   press **Calibrate speaker**, then **Start RX** and grant microphone permission.
   Complete receive windows appear in Received. Plain **Monitor** needs no time
   correction but cannot supply the timed FT8 RX prerequisite for sending.
4. Tap a received message for details. **Use peer** selects a complete decoded CB sender and suggests a reply,
   opposite period and TX audio offset without enabling TX. Review the message,
   offset, level and period. For manual output press **Enable TX**, then
   **Send one slot**. Advice can suggest the next message without sending it.
   **Auto CQ** or **Auto answer** explicitly starts a bounded automatic exchange.
   See [manual operation](docs/MANUAL_OPERATION.md), [QSO advice](docs/QSO_ADVICE.md)
   and [AutoSeq](docs/AUTOSEQ.md).
5. Navigation does not cancel an operating session. Use **Stop TX** to cancel
   output/AutoSeq or **Stop session** to end RX and TX together. Process death
   never restarts either. Message tools bench previews have their own foreground
   lifetime; see [continuous sessions](docs/CONTINUOUS_RECEPTION.md).
6. A completed local exchange becomes a pending review in **Logbook**. Confirm
   the actual radio dial before saving. Saved contacts support Worked filtering;
   pending reviews do not. Use **Import/Export/Share ADIF** for saved contacts, or
   **Back up log / Restore backup** to preserve pending reviews and full metadata.
   Review imported calls, UTC and frequencies before applying the selected entries.

For desktop monitoring, select **WSJT-CB LAN** under **Settings → Audio & source**,
configure the listening port in **Settings → WSJT-CB LAN**, then start RX. Configure
the desktop to send UDP to the phone's Wi-Fi IPv4 address and the same port,
normally 2237. See [LAN setup and Replay](docs/WSJT_CB_INTEGRATION.md#desktop-setup-and-phone-test).

Additional tools are documented separately:

- [Microphone capture](docs/MICROPHONE_CAPTURE.md), [spectrum/waterfall](docs/SPECTRUM.md)
  and [live reception](docs/LIVE_RECEIVE.md) for input diagnostics and decoding.
- [Message/WAV generation](docs/OFFLINE_WAVEFORM.md), [recorded WAV decoding](docs/RECORDED_SLOTS.md)
  and [speaker/UTC bench tools](docs/PHONE_AUDIO.md) for explicit file/audio tests.
- [Hamlib catalog/Dummy checks](docs/HAMLIB.md) and [USB inventory](docs/USB_CAT.md)
  for configuration and software diagnostics.
- [Session diagnostics](docs/SESSION_DIAGNOSTICS.md) for optional bounded technical
  reports. Reports contain no audio or message text and never upload telemetry.

Android's document picker may use the phone's system language; app controls remain
English. File operations need no broad storage permission. Settings, profiles,
saved contacts and pending reviews are durable; measured time, calibration,
message/hash sessions and unsaved drafts are temporary. See
[the interface guide](docs/INTERFACE.md) and [documentation index](docs/README.md).

## Build from source

Supported automated setup: Linux x86_64, Python 3.12+, Git, GNU Make, `patch`, `curl`,
`unzip`, Internet access and the standard Linux `timeout` command.

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
timeout 600 bash scripts/check.sh
```

Bootstrap installs project-local JDK/Android tooling in ignored `.tooling/`, checks
download checksums and prompts for SDK licenses. It does not install system
packages or Android Studio. The check builds app/probe/device-check APKs and runs
JVM tests and lint; desktop-reference checks need separate host compilers.

- App: `app/build/outputs/apk/debug/app-debug.apk`
- Development probe: `engineprobe/build/outputs/apk/debug/engineprobe-debug.apk`
- Device checks: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Use `--no-daemon` for additional Gradle commands.
[Development setup](docs/DEVELOPMENT.md) covers Android Studio, other systems,
reference compilers, CI artifacts and phone deployment.

| Tool/platform | Repository setting |
| --- | --- |
| Android | Minimum API 26 (Android 8.0); compile/target API 36 |
| Native ABIs | `arm64-v8a`, `x86_64`; no ARM32 build |
| JVM / Gradle | JDK 17, Gradle 8.14.6, AGP 8.13.2, Kotlin 2.4.21 |
| Compose / native tools | BOM 2025.10.01, NDK 28.2.13676358, CMake 3.22.1 |

## Compatibility and verification

WSJT-CB reference: **`94f776d54946fe43db5e0bb9c966b1595c7b7d97`**. Kotlin implements
selected CB text/hash algorithms; source-built ft8_lib handles raw FT8 payloads
and PCM. The complete WSJT-CB desktop engine is not bundled. Initial WSJT-X
3.0.0-rc1 provenance and later comparisons are recorded in the
[compatibility inventory](docs/WSJT_CB_COMPATIBILITY.md).

[Validation](docs/VALIDATION.md) separates current qualification from historical
results. Coverage includes message/hash/channel fixtures, physical acoustic
exchanges, continuous sessions, 15-minute reception tests on A51 and S25 FE, and
ADIF/SQLite/file checks. Finite device tests do not establish all-device support,
battery life, desktop decoder sensitivity parity or RF interoperability.
Recorded SNR remains experimental; unknown hashes never identify an invented peer.
See [decoder profiles](docs/DECODER_PROFILES.md) for bounded work and
[acoustic interoperability](docs/ACOUSTIC_INTEROP.md) for independent desktop
checks of recorded phone output.

## Documentation and contributions

Start with the [documentation index](docs/README.md),
[architecture](docs/ARCHITECTURE.md) and [development guide](docs/DEVELOPMENT.md).
Contributions should describe one verifiable behavior, include relevant tests
and update affected English guides. Hardware-dependent changes need actual
Android/audio/USB/radio evidence; CI alone does not establish hardware support.

## License and commercial use

**GPL-3.0-only**: see [LICENSE](LICENSE), [third-party notices](THIRD_PARTY_NOTICES.md)
and [commercial distribution](docs/COMMERCIAL_DISTRIBUTION.md). Selling copies is
permitted subject to GPL obligations, including Corresponding Source and recipient
rights; attribution alone is insufficient. Signing material, local tooling,
credentials, private recordings and backups do not belong in Git.

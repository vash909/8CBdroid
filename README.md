# 8CBdroid

Android development skeleton for FT8 on CB / 11 metres, with planned
compatibility with [WSJT-CB](https://github.com/vash909/WSJT-CB).

**Status: early development.** A debug APK can be built.
Station callsign and locator can be saved and restored after reopening the app.
The LAN tab shows WSJT-CB status and live/replayed decodes, with explicit replay
and local clear controls. Decode history holds up to 200 messages and is cleared
when the peer disconnects or listening stops. Live local FT8, USB radio control, audio
and logging are not implemented yet. The entire client uses English.
The Radio tab can discover USB interfaces and request access; radio connection
and CAT/PTT commands remain unimplemented.
An experimental source-built offline FT8 channel backend and a separate phone
probe APK are available. The **Messages** tab previews CB text encoding offline,
including loss warnings. Its offline receive simulator can learn calls per
session, commit pending identities and show unknown or ambiguous hashes.
Local operating controls remain pending. See [message preview and limits](docs/CB_MESSAGE_CODEC.md)
and [hash sessions and phone checks](docs/CB_HASH_SESSIONS.md).
The Messages tab can also generate and save an offline FT8 WAV with explicit
audio frequency/peak level and confirmation of lossy encodings. It never plays
audio or keys a radio. See [waveform checks and deferred phone tests](docs/OFFLINE_WAVEFORM.md).
See [engine scope and verification](docs/FT8_ENGINE.md).

In **Station**, enter a complete CB callsign and a 4- or 6-character Maidenhead
locator, then tap **Save settings**. Wait for confirmation before closing.
[Phone verification steps](docs/DEVELOPMENT.md#phone-checks).

For LAN monitoring, tap **Start listening** and configure WSJT-CB's UDP server
to this phone's Wi-Fi IPv4 address and selected port (default 2237). Keep the
app in the foreground. See [LAN setup and testing](docs/WSJT_CB_INTEGRATION.md#desktop-setup-and-phone-test),
including the PC-side `scripts/wsjtcb_lan_probe.py` simulator.

For USB interface discovery and permission checks, see the [USB CAT guide](docs/USB_CAT.md).

## Planned connection paths

- **USB OTG / CAT:** radio control through Hamlib/JNI or equivalent Android
  CAT drivers. CAT/PTT and USB audio use separate paths.
- **VOX:** phone microphone and speaker for acoustic coupling to a radio
  with VOX enabled.
- **LAN:** WSJT-CB's existing binary UDP protocol for status, decodes, logs
  and supported commands. These APIs do not carry audio.

## Quick start on this PC / Linux x86_64

Requirements: Python 3.12+, `curl`, `unzip`, Git and Internet access.

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
bash scripts/controlled.sh 600 bash scripts/check.sh
```

Bootstrap downloads JDK 17 and Android SDK into `.tooling/`, checks the
published checksums and displays SDK licenses. It installs no system packages.
Local tooling and `local.properties` are excluded from Git.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

With a connected phone and USB debugging authorized:

```bash
source scripts/env.sh
adb devices
./gradlew :app:installDebug
```

In Android Studio, open the repository root and select Gradle JDK 17
(`.tooling/jdk-17` on this PC). `local.properties` points to the local SDK.
For other systems, see the [development guide](docs/DEVELOPMENT.md).

## Structure

| Path | Responsibility |
| --- | --- |
| `app/` | English Compose UI, station/LAN/message ViewModels, DataStore and hardware placeholders |
| `core/` | Station models, CB callsigns, offline message codec/hash sessions, PCM generation/WAV writer and radio/audio/DSP contracts |
| `protocol/wsjtcb/` | Wire codec, UDP status/decodes, explicit replay and peer lifetime |
| `dsp/ft8/` | Android offline channel backend and JNI adapter |
| `native/` | Pinned ft8_lib channel sources and host checks |
| `engineprobe/` | Separate no-permission offline phone test APK |
| `scripts/` | Local environment setup and checks |
| `.github/workflows/` | Build, JVM tests, lint and debug APK on GitHub Actions |

Tests include DataStore file persistence across reopening, settings validation,
loading/saving failures, duplicate-save prevention and real loopback UDP sessions. Develop on focused
branches with short PR descriptions. Merge only after the owner's explicit OK,
once the PR build has been checked.

Pinned versions: JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21,
Compose BOM 2025.10.01, compile/target SDK 36, minimum Android 8.0 / API 26.
Native build: NDK 28.2.13676358, CMake 3.22.1, arm64-v8a/x86_64.
The Linux controlled wrapper requires a systemd user session; other systems can
run `bash scripts/check.sh` directly with Gradle limited to two workers.

## Compatibility work ahead

The profile uses WSJT-CB's FT8 parameters (15-second slots, 12 kHz DSP PCM,
79 symbols) and default frequencies of 27.265 / 27.045 MHz. Callsign validation
follows its complete CB grammar; **it is not an FT8 encoder**. Hashes, type 4
messages and CB sequences need interoperability tests against the desktop engine.

The inspected reference is WSJT-CB `94f776d54946fe43db5e0bb9c966b1595c7b7d97`.
The desktop engine is not downloaded or compiled as an app dependency.
Host-only reference tests cover CB predicates, 77-bit payloads, hash learning,
message parsing/generation and AutoSeq decisions using pinned desktop routines.
See the [packing/hash fixtures](reference/wsjtcb/PACKING.md) and
[QSO traces](reference/wsjtcb/QSO.md) for reproduction and limits.

- [Architecture and Hamlib/USB/audio integration](docs/ARCHITECTURE.md)
- [LAN contract verified against upstream sources](docs/WSJT_CB_INTEGRATION.md)
- [Environment, build and debugging](docs/DEVELOPMENT.md)
- [CB difference inventory and desktop reference fixtures](docs/WSJT_CB_COMPATIBILITY.md)
- [Roadmap](docs/ROADMAP.md)

## License and commercial use

GPL-3.0-only; see [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).
Selling the app is permitted under GPLv3's conditions, including Corresponding
Source and recipient rights. Repository privacy does not remove those obligations.
See the [commercial distribution policy](docs/COMMERCIAL_DISTRIBUTION.md).

Recorded-slot reception (R08): the **Audio** tab imports a 15-second mono PCM16
WAV at 12 kHz with an explicit UTC boundary. Results include CB text/hash state,
offset, DT and experimental SNR; live microphone reception remains pending. See
[recorded-slot setup and limits](docs/RECORDED_SLOTS.md).

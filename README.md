# 8CBdroid

An Android FT8 client in development for CB / 11 metres, built around
[WSJT-CB](https://github.com/vash909/WSJT-CB) message compatibility. The app and
project documentation use English.

**Current state:** LAN monitoring, offline CB message encoding, WAV generation
and recorded-slot decoding are implemented. Microphone/speaker operation,
CAT/PTT, live local reception, AutoSeq and QSO logging remain unimplemented.
The app cannot yet make a radio QSO.

## What works

| Screen | Available behavior | Current boundary |
| --- | --- | --- |
| **Station** | Save callsign/locator; restore them after reopening; view LAN activity. | Operating frequency/profile and local RX/TX controls are placeholders. |
| **Radio** | Discover USB interfaces and request access to recognized serial adapters. | No serial port, CAT connection, frequency command or PTT. |
| **Audio** | Import and decode one recorded FT8 WAV with supplied UTC, CB text/hash flags, offset, DT and estimated SNR. | Exactly 15 seconds, mono PCM16, 12 kHz, at most 1 MiB. No capture, playback or conversion. |
| **LAN** | Monitor WSJT-CB heartbeat/status and up to 200 live/replayed decodes; request Replay and clear the local list. | IPv4 unicast, one desktop, foreground only. No log import or TX commands. |
| **Messages** | Preview CB packing/losses, simulate a hash-learning session, generate and export an offline FT8 WAV. | No audio output, radio transmission or automatic sequencing. |

The intended connection paths are acoustic phone microphone/speaker with radio
VOX, documented USB CAT/audio hardware, and a separate WSJT-CB LAN companion
mode. USB discovery alone establishes neither CAT nor USB audio support. LAN
packets carry desktop status/messages, not audio.

## Try the current app

Download **8CBdroid-debug** from a successful
[GitHub Actions run](https://github.com/vash909/8CBdroid/actions/workflows/android.yml).
These are development APKs, not release builds. The agent deploys the matching
CI APK through ADB to the authorized debug phone and asks when it needs unlocking.
A debug-signature conflict requires approval before reinstalling and restoring
settings; see [deployment instructions](docs/DEVELOPMENT.md#deploy-and-test-on-a-phone).

1. In **Station**, save a complete CB callsign and a 4- or 6-character locator,
   for example `1AT106` / `JN45AB`. Wait for the save confirmation.
2. In **Messages**, preview `CQ 1AT106`. Generate an offline WAV at 1000 Hz / 40%,
   then save it through Android's document picker. Content changes require consent.
3. In **Audio**, enter a known UTC boundary such as `2026-11-06T12:00:00Z` and
   select that WAV. Expect `CQ 1AT106`, approximately 1000 Hz and DT +0.30 s.
   This example assigns UTC context to a test file; it does not measure a clock.
4. For desktop monitoring, start listening in **LAN**, then configure WSJT-CB to
   send UDP to the **phone's Wi-Fi IPv4 address** and the same port, normally 2237.
   [LAN setup and Replay](docs/WSJT_CB_INTEGRATION.md#desktop-setup-and-phone-test)
   describe the desktop request setting and foreground lifecycle.

Android's system document picker can use the phone's own language. App controls
and messages remain English. Ordinary WAV import/export requires no broad
storage permission. The saved station is persistent; hash sessions, decoded
recordings and generated PCM are temporary. Exported documents remain in the
chosen location.

## Build from source

Supported automated setup: Linux x86_64, Python 3.12+, Git, `curl`, `unzip`,
Internet access and a systemd user session for controlled builds.

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
bash scripts/controlled.sh 600 bash scripts/check.sh
```

Bootstrap installs project-local JDK/Android tooling in ignored `.tooling/`,
checks download checksums and prompts for SDK licenses. It does not install
system packages or Android Studio. The check builds both APKs and runs JVM
tests/lint; the separate desktop-reference checks need host compilers.

- App: `app/build/outputs/apk/debug/app-debug.apk`
- Offline test probe: `engineprobe/build/outputs/apk/debug/engineprobe-debug.apk`

On this PC, heavy jobs use **one job, two CPU cores, 16 GiB maximum RAM, no swap
and a finite deadline**. Use `--no-daemon` for additional Gradle commands.
[Development setup](docs/DEVELOPMENT.md) covers Android Studio, other systems,
reference compilers, CI artifacts and safe phone deployment.

| Tool/platform | Repository setting |
| --- | --- |
| Android | Minimum API 26 (Android 8.0); compile/target API 36 |
| Native ABIs | `arm64-v8a`, `x86_64`; no ARM32 build |
| JVM / Gradle | JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21 |
| Compose / native tools | BOM 2025.10.01, NDK 28.2.13676358, CMake 3.22.1 |

## Compatibility and verification

WSJT-CB reference: **`94f776d54946fe43db5e0bb9c966b1595c7b7d97`**. Kotlin implements
selected CB text/hash algorithms; a source-built ft8_lib channel backend handles
raw FT8 payloads and PCM. The complete WSJT-CB desktop engine is not bundled.
The original WSJT-X 3.0.0-rc1 snapshot and later subsystem comparisons have
separate recorded provenance in the [compatibility inventory](docs/WSJT_CB_COMPATIBILITY.md).

[Validation evidence](docs/VALIDATION.md) records the merged implementation,
exact tested commits/CI runs and Samsung A51 results. Coverage includes 154
message cases, 96 hash scenarios, 131 channel vectors, 21 generated-waveform
checks and 12 recorded-slot cases. These corpora do not establish full desktop
decoder parity, weak-signal sensitivity, sustained phone operation or RF
interoperability. Recorded SNR is experimental; unknown hashes never identify
an invented peer. Import into the full WSJT-CB desktop GUI remains a separate
pending check.

## Documentation and contributions

Start with the [documentation index](docs/README.md),
[architecture](docs/ARCHITECTURE.md) and [development guide](docs/DEVELOPMENT.md).
The [roadmap](docs/ROADMAP.md) describes planned work; verified capability and
review outcomes are recorded in [validation](docs/VALIDATION.md).

Use focused branches and short English PR descriptions. Run relevant local
checks and CI, deploy/test on the available debug phone, then wait for the
owner's explicit merge approval. Repository instructions are in [AGENTS.md](AGENTS.md).

## License and commercial use

**GPL-3.0-only**: see [LICENSE](LICENSE), [third-party notices](THIRD_PARTY_NOTICES.md)
and [commercial distribution](docs/COMMERCIAL_DISTRIBUTION.md). Selling copies
is permitted subject to GPL obligations, including Corresponding Source and
recipient rights; attribution alone is insufficient. Signing material, local
tooling, credentials and private recordings do not belong in Git.

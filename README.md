# 8CBdroid

An Android FT8 client in development for CB / 11 metres, built around
[WSJT-CB](https://github.com/vash909/WSJT-CB) message compatibility. The app and
project documentation use English.

**Current state:** LAN monitoring, offline CB message encoding, WAV generation
and recorded-slot decoding are implemented, along with foreground microphone capture,
NTP/GPS time correction, UTC slot assembly, live local decoding and a microphone spectrum/waterfall. Speaker output,
CAT/PTT, AutoSeq and QSO logging remain unimplemented.
The app cannot yet make a radio QSO.

## What works

| Screen | Available behavior | Current boundary |
| --- | --- | --- |
| **Station** | Save callsign/locator and NTP server preferences; synchronize app time with NTP/GPS; view LAN activity. | Operating frequency/profile and local RX/TX controls are placeholders. |
| **Radio** | Discover USB interfaces and request access to recognized serial adapters. | No serial port, CAT connection, frequency command or PTT. |
| **Audio** | Capture a selected microphone, inspect levels/processing and spectrum/waterfall, select RX/TX cursors, assemble UTC slots and decode local FT8 with Fast/Standard profiles; separately save audio or import/decode WAVs. | Live reception needs measured NTP/GPS time and the foreground. Latest 60 local frames and 96 waterfall rows; Stop clears results/history/hash learning. Cursors do not tune or transmit. No playback. WAV import requires 15 seconds, mono PCM16, 12 kHz, at most 1 MiB. |
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
3. In **Audio**, scroll to **Decode a recorded slot**, enter a known UTC boundary such as `2026-11-06T12:00:00Z` and
   select that WAV. Expect `CQ 1AT106`, approximately 1000 Hz and DT +0.30 s.
   This example assigns UTC context to a test file; it does not measure a clock.
4. In **Audio → Microphone input**, select an input, press **Start microphone**
   and grant permission. Confirm the actual route and input levels. Stop before
   saving the most recent five seconds; this is a capture diagnostic, not live FT8.
   [Microphone capture](docs/MICROPHONE_CAPTURE.md) describes processing and lifecycle.
5. In **Station → Time synchronization**, select a regional or custom NTP server
   (IP/hostname), or measure GPS with precise location permission. Apply the valid
   measurement, then enable **Assemble UTC slots** in Audio. Stop before exporting
   the latest complete 15-second WAV. [UTC slots](docs/UTC_RECEIVE_SLOTS.md) describes
   expiry, device requirements and timing limits. There is no manual offset.
   GPS timing was verified on the S25 FE; the A51 did not obtain a fix. See
   [device evidence and limits](docs/VALIDATION.md#r11-time-synchronization-and-utc-capture).
6. For live local reception, enable **Decode live local FT8** in Audio, choose
   **Local Standard** or **Local Fast** and press **Start microphone**. Complete
   UTC slots are decoded automatically; inspect **Live local decodes** below the
   capture controls. Stop clears these results and the local callsign cache.
   See [live reception](docs/LIVE_RECEIVE.md) for work limits and phone checks.
7. For desktop monitoring, start listening in **LAN**, then configure WSJT-CB to
   send UDP to the **phone's Wi-Fi IPv4 address** and the same port, normally 2237.
   [LAN setup and Replay](docs/WSJT_CB_INTEGRATION.md#desktop-setup-and-phone-test)
   describe the desktop request setting and foreground lifecycle.

Android's system document picker can use the phone's own language. App controls
and messages remain English. Ordinary WAV import/export requires no broad
storage permission. The saved station and server preferences are persistent; measured time, hash sessions, decoded
recordings and generated PCM are temporary. Exported documents remain in the
chosen location.

See [spectrum and waterfall](docs/SPECTRUM.md) for zoom, markers, cursor selection and measurement limits.

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
system packages or Android Studio. The check builds the app, probe and device-check APKs and runs JVM
tests/lint; the separate desktop-reference checks need host compilers.

- App: `app/build/outputs/apk/debug/app-debug.apk`
- Development test probe: `engineprobe/build/outputs/apk/debug/engineprobe-debug.apk`

On this PC, heavy jobs use **one job, two CPU cores, 16 GiB maximum RAM, no swap
and a finite deadline**. Use `--no-daemon` for additional Gradle commands.
[Development setup](docs/DEVELOPMENT.md) covers Android Studio, other systems,
reference compilers, CI artifacts and safe phone deployment.
[Decoder profiles](docs/DECODER_PROFILES.md) describe bounded admission,
cooperative cancellation and the finite phone benchmark.

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
an invented peer. Actual phone-exported CQ and both directed hash orientations
also passed file decoding in the complete WSJT-CB desktop GUI; see the
[R07 closure evidence](docs/VALIDATION.md#r07-closure-checks-pr-26).

## Documentation and contributions

Start with the [documentation index](docs/README.md),
[architecture](docs/ARCHITECTURE.md) and [development guide](docs/DEVELOPMENT.md).
The [roadmap](docs/ROADMAP.md) describes planned work; verified capability and
review outcomes are recorded in [validation](docs/VALIDATION.md).

Use focused branches and short English PR descriptions. Run relevant local
checks and CI, then deploy/test on the available debug phone. The agent may merge
without another OK only after completing all relevant owner phone checks under
the conditional authorization in [AGENTS.md](AGENTS.md); otherwise explicit
owner approval is required.

## License and commercial use

**GPL-3.0-only**: see [LICENSE](LICENSE), [third-party notices](THIRD_PARTY_NOTICES.md)
and [commercial distribution](docs/COMMERCIAL_DISTRIBUTION.md). Selling copies
is permitted subject to GPL obligations, including Corresponding Source and
recipient rights; attribution alone is insufficient. Signing material, local
tooling, credentials and private recordings do not belong in Git.

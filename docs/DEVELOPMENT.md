# Development environment

Use this guide for reusable setup, check and deployment commands. Feature
behavior is documented in the [index](README.md); revision-specific test outcomes
are in [validation](VALIDATION.md). Commands below run from the repository root.

## Linux x86_64 setup

Requirements: Python 3.12+, Git, GNU Make, `patch`, `curl`, `unzip`, `timeout`
and Internet access. Hamlib is compiled from its hash-verified release source;
the release archive includes generated configure files, so autotools are not needed.

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
timeout 600 bash scripts/check.sh
```

Bootstrap uses official Adoptium/Google metadata, verifies published checksums,
installs tools in ignored `.tooling/`, and prompts for SDK license acceptance.
It records the JDK/CLI download metadata in `.tooling/*-source.json`, reuses
existing tool directories and writes untracked `local.properties`. It installs
neither system packages, Android Studio nor emulator images. Refreshing a tool
is an explicit developer action; keep the recorded metadata with local diagnostics.

Source `scripts/env.sh` in each terminal. It selects local JDK/SDK directories
when present and sets the Gradle cache under `.tooling/gradle-home`. It does not
install a host Fortran/Qt toolchain or change global shell configuration.

| Component | Repository setting / location |
| --- | --- |
| JDK | 17; local `.tooling/jdk-17` |
| Gradle / AGP / Kotlin | Wrapper 8.14.6 / 8.13.2 / 2.4.21 |
| Android SDK | compile/target 36; Build Tools 35.0.0; minimum API 26 |
| Native tools | NDK 28.2.13676358, CMake 3.22.1 |
| Packaged native ABIs | `arm64-v8a`, `x86_64`; ARM32 is absent |

Version declarations live in `gradle/libs.versions.toml`,
`gradle/wrapper/gradle-wrapper.properties` and the app/library Gradle files.

## Android Studio and other systems

Install JDK 17 and the selected Android SDK/NDK/CMake through Android Studio or
command-line tools. Set `JAVA_HOME`/`ANDROID_HOME` as needed and create untracked
`local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Open the repository root in Android Studio, select Gradle JDK 17 and wait for
sync. No global Gradle installation is needed. The bootstrap targets Linux; on
another system use the installed toolchain and run
`bash scripts/check.sh` with Python, GNU Make and `patch` available. Native
Hamlib preparation supports Linux/macOS hosts. On Windows use WSL2 with Linux
JDK/SDK/NDK and build tools; running only `gradlew.bat` is not a supported native
build setup. Gradle limits worker concurrency to two; adapt command timeouts to your
hardware when needed.

## Build and check

From the repository root:

```bash
source scripts/env.sh
timeout 600 bash scripts/check.sh
```

The check runs `:core:test`, `:protocol:wsjtcb:test`, `:protocol:ntp:test`, app JVM tests, app/audio/DSP/Hamlib/probe
lint, the app/probe debug APK builds and the Android device-check APK. It uses `--no-daemon`. JVM tests read committed
reference data and need no host Fortran/Qt compiler; building the JNI dependency
does need the selected Android NDK/CMake.

| Output | Path |
| --- | --- |
| Main app | `app/build/outputs/apk/debug/app-debug.apk` |
| Development instrumentation probes | `engineprobe/build/outputs/apk/debug/engineprobe-debug.apk` |
| App device checks | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| JVM test reports | `core/build/reports/tests/`, `protocol/wsjtcb/build/reports/tests/`, `app/build/reports/tests/` |
| Lint reports | Each Android module's `build/reports/lint-results-debug.*` |

Use `--no-daemon` for additional Gradle commands. The Linux examples use
`timeout` to bound long-running commands; adjust deadlines to the build host.
Documentation-only changes need document/link/command review rather than
rebuilding or redeploying the unchanged application. The GitHub workflow also
runs for main pushes.

## Desktop reference and native checks

These are separate from `scripts/check.sh`. Use host GNU Fortran, a C++17
compiler, Boost headers, CMake/Ninja, `pkg-config` and Qt 5 Core/Network development
packages. With system packages, no compiler environment overrides are needed.
Run the following sequentially on Linux. For system-installed CMake, replace
its SDK path with `cmake`:

```bash
source scripts/env.sh
timeout 180 python3 scripts/wsjtcb_reference.py --check
timeout 180 python3 scripts/wsjtcb_packing.py --check
timeout 180 python3 scripts/wsjtcb_packing.py --check --optimization O2
timeout 180 python3 scripts/wsjtcb_qso.py --check
timeout 180 python3 scripts/wsjtcb_qso.py --check --optimization O2
timeout 180 python3 scripts/wsjtcb_context.py --check
timeout 180 python3 scripts/wsjtcb_context.py --check --optimization O2
timeout 180 python3 scripts/wsjtcb_acoustic.py --check
timeout 60 python3 scripts/wsjtcb_codec.py --check
timeout 180 python3 scripts/wsjtcb_channel.py --check
timeout 60 "$ANDROID_HOME/cmake/3.22.1/bin/cmake" -S native -B .tooling/native-host -G Ninja -DCMAKE_BUILD_TYPE=Release
timeout 120 "$ANDROID_HOME/cmake/3.22.1/bin/cmake" --build .tooling/native-host --parallel 2
timeout 120 .tooling/native-host/control_checks
timeout 120 python3 scripts/check_native.py .tooling/native-host
timeout 180 python3 scripts/wsjtcb_pcm.py .tooling/native-host
timeout 180 python3 scripts/wsjtcb_recordings.py .tooling/native-host
timeout 240 python3 scripts/ft8_benchmark.py .tooling/native-host
timeout 180 python3 -m unittest discover -s scripts/tests -p 'test_wsjtcb_*.py'
```

`FC`/`FFLAGS`, `CXX` and relevant Qt/pkg-config/linker variables select existing
host toolchains; use absolute paths for extracted installations. See
[reference infrastructure](../reference/wsjtcb/README.md) for checksums,
regeneration and optional online source audits. Do not normalize original
vendored source bytes or regenerate expected results just to make a failure pass.
The WSJT-CB test adapter has supplied time/frequency for PCM checks; it is not
the complete desktop decoder or an RF test.

## Deploy and test on a phone

Use a phone with USB debugging enabled and authorize the development computer.
Download artifacts from a successful CI run, verify that the run matches the
revision being tested, and use app/probe/test APKs from the same run so their
debug signatures match. Record the revision, CI run, device, Android version,
checks and limitations with test results.

Use `adb -s SERIAL` to select a device when several are connected. Attempt an
in-place update first to preserve app data. Different CI/local builds can use
different debug keys: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means Android cannot
update the installed package with that signer. Keep existing data intact; use a
separate test device or deliberately back up data before any destructive
reinstallation. Never publish settings, backups or device serial numbers.

Keep the phone unlocked for UI checks. Restore temporary device settings after
testing. A disconnected, unauthorized or locked device is not a completed test.

For a local build with the same installed signing key:

```bash
source scripts/env.sh
timeout 10 adb devices -l
timeout 45 adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
timeout 15 adb shell am start --user 0 -W -n net.vash.ft8cbdroid/.MainActivity
```

The local helper below installs **only the locally built probe**, not the CI
artifacts and not the main app. It stops only its probe package on exit:

```bash
source scripts/env.sh
timeout 120 bash scripts/probe_phone.sh
```

It also refuses a failed ADB update through `set -e`; do not resolve its signing
conflict by deleting data. For CI device evidence, use the verified CI probe APK
with bounded instrumentation, rather than letting this helper replace it with
a local build. After installing the verified CI probe APK:

```bash
timeout 90 adb shell am instrument --user 0 -w -r net.vash.ft8cbdroid.engineprobe/.OfflineEngineProbe
```

There is no launcher icon for the probe. Inspect its explicit `PASS` and
`INSTRUMENTATION_CODE: -1`; a shell exit code alone is insufficient.
The [validation record](VALIDATION.md) shows the latest accepted A51 results.
For decoder benchmarks, use the installed verified CI probe with
`timeout 240 bash scripts/profile_phone.sh`;
[the profile guide](DECODER_PROFILES.md) defines the finite corpus, sampling,
thermal scope and private local report. This helper does not install APKs.

## Phone checks

Use a dedicated test identity or record the current station before editing it;
restore the original settings after tests.

| Area | Review steps |
| --- | --- |
| Station | Save a valid call/locator, wait for confirmation, rotate and force-stop/reopen; verify restoration. Invalid drafts such as `1AT` or `SS99` must not save. Unsaved edits must not replace the saved identity. |
| LAN | Follow [desktop setup](WSJT_CB_INTEGRATION.md#desktop-setup-and-phone-test); test Replay/Clear, foreground stop, peer loss and port reuse. |
| USB | Follow [discovery/permission checks](USB_CAT.md#phone-verification). An empty inventory needs no OTG; real permission/detach/CAT evidence needs the actual hardware. |
| CB messages | Follow [codec checks](CB_MESSAGE_CODEC.md#phone-verification) and [hash sessions](CB_HASH_SESSIONS.md#phone-verification). |
| WAV generation | Follow [generation/export checks](OFFLINE_WAVEFORM.md#phone-and-desktop-checklist), keeping desktop GUI import distinct from the host adapter. |
| Recorded reception | Follow [recorded-slot review](RECORDED_SLOTS.md#phone-review): valid/noisy files, negative cases, UTC errors, cancellation, rotation and reopening. |

The LAN simulator is `python3 scripts/wsjtcb_lan_probe.py --host PHONE_WIFI_IPV4`;
it does not replace a real WSJT-CB desktop test. Test the app with a non-English
phone locale: app text remains English, while Android system dialogs may use
the system language. No current test establishes VOX, USB audio, PTT or RF support.

## CI artifacts and release boundary

The existing GitHub workflow runs on main pushes, PRs and manual dispatch:

| Job/artifact | Contents |
| --- | --- |
| `reference` job | Host source/fixture reproduction, native channel/PCM/recording checks and Python tests. |
| `build` job | JVM tests, app/audio/DSP/Hamlib/probe lint and all development APK builds. No physical device test. |
| `8CBdroid-debug` | Main debug APK. |
| `8CBdroid-engine-probe` | Offline/acoustic instrumentation APK with test corpora. |
| `8CBdroid-device-checks` | Test-only APK targeting the debug app: R07 provider-failure/retry, UI cancellation and real encoder checks. |
| `validation-reports` | JVM test and app lint reports; uploaded even after failure when available. |

Artifacts are retained for 14 days; long-lived evidence should link the run and
record checksums/results, since downloads may expire. CI has no protected release
signing, store publication or stable debug-key provisioning. Keep signing keys,
credentials, caches, private recordings and backups out of Git. Committed
synthetic/reference WAVs are intentional test fixtures with documented provenance.

## Microphone checks

R10 adds a separate, opt-in acoustic probe that records a short speaker reference
tone on an unlocked phone. Its microphone permission does not change the ordinary
offline probe behavior. See [capture procedure and limitations](MICROPHONE_CAPTURE.md#verification).

## R07 device checks

Install the successful CI main app and `8CBdroid-device-checks` APK from the
same run/head, preserving app data. The test APK must have the matching debug
signature. Unlock the phone before running:

```bash
source scripts/env.sh
timeout 120 bash scripts/check_waveform_phone.sh
```

This test uses the real Activity, ViewModel, native encoder and document exporter.
Its test-only provider deliberately fails a write, then accepts the retry into
its own private file. It allows only the debuggable target app UID. The main
APK contains neither this provider nor the instrumentation. Saved station
settings are preserved; capture, playback and radio interfaces remain idle.

To exercise the actual Cancel button reliably, the test briefly parks Default
workers on bounded latches without CPU spinning. It checks cancellation before
encoding starts and, separately, after observing active JNI encoding. This does
not measure ordinary generation latency. Failed tests are reported as failures.
The helper restores its temporary USB stay-awake setting and stops only the
target app on exit. Reports remain in ignored `.tooling/`.

## R11 time and UTC capture checks

The app device-check APK adds `UtcPhoneChecks`, with `ntp-audio` and `gps`
scenarios. Use matching verified CI app/test APKs and the
[UTC procedure](UTC_RECEIVE_SLOTS.md#verification). These open real network,
GNSS or microphone interfaces only on explicit invocation. A failed real GPS
fix or audio timestamp check is pending qualification, not a mockable pass.

## R12 live reception checks

After deploying matching successful CI app/device-check APKs, follow
[live reception](LIVE_RECEIVE.md#verify). The explicit helper plays three pinned
desktop CB WAVs through the PC speakers while the phone records and decodes real
microphone slots. It requires an unlocked phone with microphone permission,
`pw-play` and a synchronized PC clock. It does not alter saved station settings.

```bash
source scripts/env.sh
timeout 210 bash scripts/check_live_phone.sh
```

Publish `.tooling/live-phone-report.json` findings with the tested CI commit and
device. Keep the private report itself out of Git, and separately complete actual
UI/permission/lifecycle checks when recording device qualification.

## Hamlib native catalog checks

The `radio:hamlib` module builds static Hamlib for both packaged ABIs before
CMake configures JNI. Its release archive and two local compatibility patches
are hash-pinned in `radio/hamlib/source.json`. GNU Make and `patch` must be on
PATH. Build logs are under `radio/hamlib/build/hamlib/<abi>/`; a failed build
must not be replaced with an arbitrary system/prebuilt Hamlib library.

For software-only host checks, with C/C++, CMake, Make and `patch` installed:

```bash
timeout 900 bash scripts/check_hamlib.sh
```

The host CI job runs this separately from the Android build. See
[the Hamlib guide](HAMLIB.md) for the matching phone instrumentation command,
UI checks, cancellation behavior and the boundary between catalog metadata and
physical radio qualification. No USB cable/radio is required for Dummy checks.

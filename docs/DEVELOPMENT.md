# Development environment

Use this guide for reusable setup, check and deployment commands. Feature
behavior is documented in the [index](README.md); revision-specific test outcomes
are in [validation](VALIDATION.md). Commands below run from the repository root.

## Linux x86_64 setup

Requirements: Python 3.12+, Git, `curl`, `unzip` and Internet access. Controlled
jobs additionally require cgroup v2, a systemd user session, `flock` and `timeout`.

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
bash scripts/controlled.sh 600 bash scripts/check.sh
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
| Gradle / AGP / Kotlin | Wrapper 8.13 / 8.13.2 / 2.2.21 |
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
sync. No global Gradle installation is needed. The bootstrap and controlled
wrapper target Linux; on another system use the installed toolchain and run
`bash scripts/check.sh`, or the same tasks with `gradlew.bat --no-daemon` on
Windows. The two-worker Gradle configuration still applies; equivalent CPU/RAM
and deadline limits must come from that system's tooling.

## Build and check

On this Linux PC:

```bash
source scripts/env.sh
bash scripts/controlled.sh 600 bash scripts/check.sh
```

The check runs `:core:test`, `:protocol:wsjtcb:test`, app JVM tests, app/DSP/probe
lint, and both debug APK builds. It uses `--no-daemon`. JVM tests read committed
reference data and need no host Fortran/Qt compiler; building the JNI dependency
does need the selected Android NDK/CMake.

| Output | Path |
| --- | --- |
| Main app | `app/build/outputs/apk/debug/app-debug.apk` |
| Offline instrumentation probe | `engineprobe/build/outputs/apk/debug/engineprobe-debug.apk` |
| JVM test reports | `core/build/reports/tests/`, `protocol/wsjtcb/build/reports/tests/`, `app/build/reports/tests/` |
| Lint reports | Each Android module's `build/reports/lint-results-debug.*` |

Run additional Gradle commands with `--no-daemon` inside the controlled wrapper.
Do not run a second heavy job while one is active. A documentation-only edit
needs document/link/command review rather than rebuilding or redeploying the
unchanged application; GitHub's current workflow still runs for main pushes.

## Controlled jobs on this PC

`bash scripts/controlled.sh SECONDS COMMAND ...` limits the whole process tree:
CPU quota 200% (two cores total), memory high 12 GiB, hard maximum **16 GiB**, no
swap and a TERM/KILL deadline. It also caps CMake/OpenMP/BLAS concurrency and
Java's visible CPU count. Gradle disables parallel projects and allows two workers;
16 GiB is the process-tree ceiling, not the configured JVM heap size.

An exclusive project lock prevents concurrent controlled jobs. Scopes are stopped
on completion/timeout so build daemons cannot remain. Timeout returns 124;
check progress at least once per minute and investigate before retrying. If the
wrapper cannot apply limits, fix the systemd user session instead of bypassing
it. Do not kill unrelated editor processes or delete user settings to recover.

Editor responsiveness should be diagnosed separately: inspect extension-host
logs and locally exclude `.tooling`, `.gradle` and generated `build` trees from
watchers/search. Local IDE settings are not shared build configuration.

## Desktop reference and native checks

These are separate from `scripts/check.sh`. Use host GNU Fortran, a C++17
compiler, Boost headers, CMake/Ninja, `pkg-config` and Qt 5 Core/Network development
packages. With system packages, no compiler environment overrides are needed.
On this existing PC, ignored `.tooling/qso-env.sh` selects extracted compiler/Qt
packages; `BOOST_INCLUDEDIR` must point to the extracted Boost include directory.
Those local helpers are not a clean-checkout dependency or a substitute for
installing the host prerequisites.

Run the following sequentially on Linux. For system-installed CMake, replace
its SDK path with `cmake`:

```bash
source scripts/env.sh
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_reference.py --check
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_packing.py --check
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_packing.py --check --optimization O2
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_qso.py --check
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_qso.py --check --optimization O2
bash scripts/controlled.sh 60 python3 scripts/wsjtcb_codec.py --check
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_channel.py --check
bash scripts/controlled.sh 60 "$ANDROID_HOME/cmake/3.22.1/bin/cmake" -S native -B .tooling/native-host -G Ninja -DCMAKE_BUILD_TYPE=Release
bash scripts/controlled.sh 120 "$ANDROID_HOME/cmake/3.22.1/bin/cmake" --build .tooling/native-host --parallel 2
bash scripts/controlled.sh 120 python3 scripts/check_native.py .tooling/native-host
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_pcm.py .tooling/native-host
bash scripts/controlled.sh 180 python3 scripts/wsjtcb_recordings.py .tooling/native-host
bash scripts/controlled.sh 180 python3 -m unittest discover -s scripts/tests -p 'test_wsjtcb_*.py'
```

`FC`/`FFLAGS`, `CXX` and relevant Qt/pkg-config/linker variables select existing
host toolchains; use absolute paths for extracted installations. See
[reference infrastructure](../reference/wsjtcb/README.md) for checksums,
regeneration and optional online source audits. Do not normalize original
vendored source bytes or regenerate expected results just to make a failure pass.
The WSJT-CB test adapter has supplied time/frequency for PCM checks; it is not
the complete desktop decoder or an RF test.

## Deploy and test on a phone

The agent owns deployment to the connected, authorized debug phone. After a
successful code-PR CI run:

1. Verify that the run and artifact metadata match the current PR head. Download
   **8CBdroid-debug** and, for engine changes, **8CBdroid-engine-probe**; verify
   artifact digests and installed APK hashes.
2. Use bounded ADB commands. For the A51 on this PC, `ADB_LIBUSB=1` has been used;
   it is an environment workaround, not an app requirement. Specify `-s SERIAL`
   when multiple devices are connected and select Android user 0 where necessary.
3. Attempt an in-place update that preserves data. Different CI/local runs may
   have different debug signing keys. On `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
   keep the installed app/data intact and request explicit approval before
   uninstalling or clearing anything. A source update or passing CI is not that
   approval. If approved, make a verified private backup and restore persistent
   files byte for byte before launch; do not commit backups or settings.
4. Launch the app, run the relevant probe and notify the owner when the phone
   must be unlocked. Keep the screen awake during UI checks and restore any
   temporary test setting. Report disconnected/unauthorized/locked tests as pending.
5. Record commit, CI run, device/Android, actual checks and limitations. Deployment
   does not replace phone testing or the owner's explicit merge approval.

For a developer-owned local build with the same installed signing key:

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
ADB_LIBUSB=1 bash scripts/controlled.sh 120 bash scripts/probe_phone.sh
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

## Phone checks

Use a dedicated test identity or record the current station before editing it;
restore the owner's settings after tests.

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

## Pull requests, CI and release boundary

Use focused branches and short English PR titles/descriptions. Code PRs require
local tests/lint/debug build, successful CI and relevant phone checks. Wait for
explicit owner approval before merge; do not enable auto-merge. Direct-main
work remains limited to documentation changes explicitly authorized by the owner.

The existing GitHub workflow runs on main pushes, PRs and manual dispatch:

| Job/artifact | Contents |
| --- | --- |
| `reference` job | Host source/fixture reproduction, native channel/PCM/recording checks and Python tests. |
| `build` job | JVM tests, app/DSP/probe lint and both APK builds. No physical device test. |
| `8CBdroid-debug` | Main debug APK. |
| `8CBdroid-engine-probe` | Offline instrumentation debug APK with test corpora. |
| `validation-reports` | JVM test and app lint reports; uploaded even after failure when available. |

Artifacts are retained for 14 days; long-lived evidence should link the run and
record checksums/results, since downloads may expire. CI has no protected release
signing, store publication or stable debug-key provisioning. Keep signing keys,
credentials, caches, private recordings and backups out of Git. Committed
synthetic/reference WAVs are intentional test fixtures with documented provenance.

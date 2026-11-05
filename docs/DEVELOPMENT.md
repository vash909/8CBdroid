# Development environment

## Linux x86_64

```bash
python3 scripts/bootstrap.py
source scripts/env.sh
bash scripts/check.sh
```

Bootstrap uses official Adoptium JDK 17 and Google command-line tools metadata,
checks published checksums, and records downloaded versions in `.tooling/*-source.json`.
Android Platform 36 and Build Tools 35.0.0 are explicitly selected. Existing local
tool directories are reused. To refresh JDK/CLI, rename the relevant directory and
rerun bootstrap.

Source `scripts/env.sh` in each new terminal. It selects project-local tools without
changing global shell configuration. Android Studio, emulator images and NDK are
not installed by bootstrap. The current build needs no NDK. Android Studio can be
installed separately from the [official site](https://developer.android.com/studio).

## Android Studio and other systems

Install JDK 17 and Android SDK through Android Studio or command-line tools. Select
Platform 36, Build Tools 35.0.0 and Platform Tools. Set JAVA_HOME/ANDROID_HOME and
create an untracked `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Open the repository root, select Gradle JDK 17, wait for sync and run `app`.
Windows users can run `gradlew.bat`. A global Gradle installation is unnecessary.

## Build and install

```bash
source scripts/env.sh
bash scripts/check.sh
adb devices
./gradlew :app:installDebug
adb shell am start -n net.vash.ft8cbdroid/.MainActivity
```

The check script runs domain/protocol/app JVM tests, lint and debug packaging.
The APK is `app/build/outputs/apk/debug/app-debug.apk`. Authorize USB debugging
on the connected phone before installation.

Tests cover CB callsigns, Maidenhead locators, wire parsing, real loopback UDP,
LAN ViewModel lifecycle ordering, Decode/Replay fixtures, history limits and
DataStore reopening. They do not establish
real-phone/WSJT-CB interoperability or radio/audio/DSP functionality.

## Phone checks

For station settings:

1. Open **Station**, enter `1at106` / `jn45ab`, then tap **Save settings**.
2. Wait for confirmation. Rotate the phone and switch sections; values should remain.
3. Force-stop and reopen the process:

```bash
adb shell am force-stop net.vash.ft8cbdroid
adb shell am start -n net.vash.ft8cbdroid/.MainActivity
```

4. Check the saved values are restored. Try `1AT` or `SS99`: Save should be disabled.
5. Save another valid pair and repeat. Unsaved edits must not replace stored values.

For LAN status, live decodes and replay, follow [LAN setup and verification](WSJT_CB_INTEGRATION.md#desktop-setup-and-phone-test).
A PC-side simulator is included:

```bash
python3 scripts/wsjtcb_lan_probe.py --host PHONE_WIFI_IPV4
```

Also test against real WSJT-CB, port conflicts, Stop/Start, rotation, backgrounding,
Clear, Close and peer timeout. Use an Italian or other non-English phone locale
and confirm all app screens, controls and messages remain English. English-only
resources are packaged, including library controls. Stay on the same LAN; the PC sends to the phone's address,
not the reverse. Radio/audio controls remain disabled.

For USB discovery and permission tests, follow [the USB CAT guide](USB_CAT.md#phone-verification).
The empty-device path can be checked without an OTG adapter; real USB tests need hardware.

## Desktop compatibility fixtures

The regular JVM suite reads committed desktop-generated fixtures and needs no
native compiler. To reproduce all host references, install GNU Fortran, a C++17
compiler, pkg-config and Qt 5 Core/Network development packages. Then run:

```bash
python3 scripts/wsjtcb_reference.py --check
python3 scripts/wsjtcb_packing.py --check
python3 scripts/wsjtcb_qso.py --check
python3 -m unittest discover -s scripts/tests -p 'test_wsjtcb_*.py'
```

See [reference setup and source audit](../reference/wsjtcb/README.md) and the
[CB difference inventory](WSJT_CB_COMPATIBILITY.md). The [QSO trace guide](../reference/wsjtcb/QSO.md)
also describes the extracted local toolchain on this PC. A separate CI job compiles
the pinned test-only routines and rejects stale fixtures at O0/O2. This does not enable a
local FT8 engine in the APK or require the Android NDK.

## Pull requests and CI

Use focused feature branches and short, clear English PR titles/descriptions.
After creating the PR and checking its build, wait for the owner's explicit OK
before merging. Do not enable auto-merge.

GitHub Actions runs on main pushes and PRs, executing tests, lint and debug build.
The debug APK and validation reports are kept as artifacts for 14 days. CI does
not sign releases or publish to Play Store. Run `bash scripts/check.sh` before a
code commit; do not commit tooling, caches, local.properties, credentials, signing
keys or recordings. Gradle Wrapper verifies the distribution SHA-256.

Toolchain reference: [AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

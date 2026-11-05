# Third-party notices

8CBdroid is an Android development skeleton. It does not yet include the
desktop DSP engine or Hamlib.

- [WSJT-CB](https://github.com/vash909/WSJT-CB), derived from WSJT-X by Joe
  Taylor K1JT and contributors, modified by Vash / 1AT106 / 1XZ001, with
  further credits listed upstream. Its UDP protocol, FT8 parameters and CB
  grammar serve as references at the commit pinned in the documentation.
  Upstream is distributed under GPLv3. This skeleton uses GPL-3.0-only.
  Unmodified CB predicates, FT8 message packing/unpacking and hash routines,
  together with required legacy packing/include and module dependencies, are
  vendored under `reference/wsjtcb/source/` for host-side tests only. Upstream
  paths, revision and SHA-256 values are recorded in `sources.json` and
  `packing-sources.json` in the reference directory. These sources and generated
  fixtures are not in the APK; no local Android DSP engine is implemented.
- Gradle Wrapper and Gradle: Apache-2.0. The wrapper scripts and JAR come
  from the official `v8.13.0` tag.
- Kotlin and AndroidX / Jetpack Compose: Apache-2.0.
- JUnit 4: EPL-1.0.
- [usb-serial-for-android 3.11.0](https://github.com/mik3y/usb-serial-for-android/tree/3.11.0):
  MIT, copyright 2011–2013 Google Inc. and 2013 Mike Wakerly.
  [Full license](app/src/main/assets/licenses/usb-serial-for-android.txt) is packaged
  in APK assets. Used for USB serial discovery; radio CAT remains pending.
- JDK/SDK packages downloaded for development retain their respective
  licenses and are not included in the repository or APK.

Maintain a version and license inventory for future dependencies, including
native libraries and their transitive dependencies.

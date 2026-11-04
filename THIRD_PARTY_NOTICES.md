# Third-party notices

8CBdroid is an Android development skeleton. It does not yet include the
desktop DSP engine, Hamlib or a USB serial library.

- [WSJT-CB](https://github.com/vash909/WSJT-CB), derived from WSJT-X by Joe
  Taylor K1JT and contributors, modified by Vash / 1AT106 / 1XZ001, with
  further credits listed upstream. Its UDP protocol, FT8 parameters and CB
  grammar serve as references at the commit pinned in the documentation.
  Upstream is distributed under GPLv3. This skeleton uses GPL-3.0-only.
- Gradle Wrapper and Gradle: Apache-2.0. The wrapper scripts and JAR come
  from the official `v8.13.0` tag.
- Kotlin and AndroidX / Jetpack Compose: Apache-2.0.
- JUnit 4: EPL-1.0.
- JDK/SDK packages downloaded for development retain their respective
  licenses and are not included in the repository or APK.

Maintain a version and license inventory for future dependencies, including
native libraries and their transitive dependencies.

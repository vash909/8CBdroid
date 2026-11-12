# Third-party notices

8CBdroid includes Kotlin CB message/hash algorithms and a source-built FT8
channel backend and a source-built Hamlib catalog/Dummy adapter. The complete
WSJT-CB desktop engine is not bundled.
Original desktop sources are used separately for host reference tests.

- [WSJT-CB](https://github.com/vash909/WSJT-CB), derived from WSJT-X by Joe
  Taylor K1JT and contributors, modified by Vash / 1AT106 / 1XZ001, with
  further credits listed upstream. Its UDP protocol, FT8 parameters and CB
  grammar serve as references at the commit pinned in the documentation.
  Upstream is distributed under GPLv3. 8CBdroid uses GPL-3.0-only.
  Unmodified CB predicates, FT8 message packing/unpacking and hash routines,
  desktop Radio/DecodedText code and main-window QSO methods, together with
  required legacy packing/include and module dependencies, are
  vendored under `reference/wsjtcb/source/` for host-side tests only. Upstream
  paths, revision and SHA-256 values are recorded in `sources.json` and
  `packing-sources.json` / `qso-sources.json` / `channel-sources.json` / `pcm-sources.json` / `context-sources.json` in the reference directory. These sources and generated
  test fixtures are not in the main APK. The offline native backend is described below.
- The 350-entry runtime CB division/country table in
  `core/src/main/resources/wsjtcb/countries.tsv` is derived from WSJT-CB's
  GPL country map in `logbook/AD1CCty.cpp` and is packaged in the app. Its original
  source and reused `Radio.cpp`/`Radio.hpp` are pinned by `context-sources.json`;
  the Kotlin lookup follows `Radio::cb_country_prefix`. Two display names are
  translated to English while original names remain available for comparison.
  See [CB operating context](docs/CB_OPERATING_CONTEXT.md) for provenance,
  reproduction and historical dataset limits.
- Selected WSJT-CB text packing, unpacking and callsign-hash algorithms are
  ported into `core/.../message/CbMessageCodec.kt` under GPL-3.0-only. These
  Kotlin routines are packaged in the app; the original Fortran sources remain
  test-only. The session learning/local-call behavior in `CbMessageSession.kt`
  follows the pinned `packjt77var.f90` / `fillhashvar.f90`, with explicit collision
  and capacity differences. See `docs/CB_MESSAGE_CODEC.md` and
  `docs/CB_HASH_SESSIONS.md` for provenance and limits.
- Gradle Wrapper and Gradle: Apache-2.0. The wrapper scripts and JAR come
  from Gradle 8.14.6 after the dependency update.
- Kotlin and AndroidX / Jetpack Compose: Apache-2.0.
- JUnit 4: EPL-1.0.
- Qt 5 Core and Network: host reference-test dependencies only, supplied by the
  developer/CI system under their upstream licenses (including LGPLv3/GPLv3 or
  commercial terms). No Qt library is vendored or packaged in the Android APK.
- [usb-serial-for-android 3.11.0](https://github.com/mik3y/usb-serial-for-android/tree/3.11.0):
  MIT, copyright 2011–2013 Google Inc. and 2013 Mike Wakerly.
  [Full license](app/src/main/assets/licenses/usb-serial-for-android.txt) is packaged
  in APK assets. Used for USB serial discovery; radio CAT remains pending.
- JDK/SDK packages downloaded for development retain their respective
  licenses and are not included in the repository or APK.

Maintain a version and license inventory for future dependencies, including
native libraries and their transitive dependencies.

## Offline native channel backend (R04)

- [ft8_lib](https://github.com/kgoba/ft8_lib/tree/9fec6ca39886edbf96f4f5e71edc76da5074e871),
  Karlis Goba YL3JG and contributors: MIT. Vendored source inventory and SHA-256
  hashes: `native/third_party/ft8_lib/manifest.json`. The channel encoder, CRC,
  LDPC decoder and spectrum monitor are compiled into `libeightcbdroid_ft8.so`.
  `message.c`/`text.c` are host-only comparison inputs, excluded from Android.
  The waveform adapter in `native/engine.cpp` follows its MIT-licensed demo.
- KISS FFT, copyright 2003–2010 Mark Borgerding: BSD-3-Clause, included through
  ft8_lib. Full notices are in `native/third_party/ft8_lib/fft/COPYING` and APK
  assets `licenses/KISSFFT-BSD-3-Clause.txt`; the MIT notice is also packaged.
  The BSD notice is reproduced from the upstream license text with the copyright
  identity from the vendored FFT headers.
- Android NDK 28.2.13676358 toolchain/runtime: the statically linked LLVM libc++
  runtime is covered by Apache-2.0 WITH LLVM-exception. Its full toolchain notice
  is copied into APK assets by the library build. NDK tools themselves are not shipped.
- WSJT-CB's original channel encoder, CRC wrapper, LDPC generator and GFSK waveform
  routines are vendored under `reference/wsjtcb/source/channel/` for host-only
  fixture generation. `channel-sources.json` pins nine additional files. Boost
  headers are a host-only dependency (Boost Software License 1.0); Boost and
  Fortran/Qt runtimes are not packaged in the app. The separate probe APK includes
  generated desktop WAVs for tests; the main APK excludes these fixtures.
- R07 adds four unchanged desktop LDPC/CRC/parity/helper files under
  `reference/wsjtcb/source/pcm/`, pinned by `pcm-sources.json`. They are GPLv3
  host-only verification sources, never part of the Android native library.
  See `docs/OFFLINE_WAVEFORM.md` for adapter boundaries and verification limits.

## Generated audio fixtures

R08 adds deterministic noisy, shifted-onset and corrupted-data derivatives of
pinned desktop-generated WAVs under `reference/wsjtcb/recordings/`, plus a noise-only
control. The manifest records input provenance, parameters, hashes and expected
outcomes; `scripts/wsjtcb_recordings.py` reproduces them. These are reference-test
assets in the separate probe APK, excluded from the main app. No personal or
radio recording is included. See [recorded-slot qualification](docs/RECORDED_SLOTS.md).

## Hamlib radio catalog

- [Hamlib 4.7.2](https://github.com/Hamlib/Hamlib/tree/40f63488fe0bd751b147f48d62fd217bf53713a0),
  The Hamlib Group and listed contributors: library code is LGPL-2.1-or-later;
  upstream utilities/examples have their own GPL-2.0-or-later terms and are not
  packaged. The library is statically linked into `libeightcbdroid_hamlib.so`.
  Exact release archive and patch hashes are in `radio/hamlib/source.json`;
  local modifications are in `radio/hamlib/patches/`. Source is downloaded and
  verified during builds, not replaced with a committed prebuilt binary.
- Hamlib bundles cJSON, copyright 2009–2017 Dave Gamble and contributors, MIT.
- Full upstream LGPL/GPL text, license description, authors and cJSON notice are
  retained under `radio/hamlib/src/main/assets/licenses/` and packaged in APKs.
  The existing Android NDK toolchain notice also covers the static C++ runtime.
- The JS8Call Android USB bridge patch was reviewed for compatibility but is not
  included in this implementation. No JS8Call transport code is packaged.

[Hamlib integration](docs/HAMLIB.md) records exclusions and reproduction commands.
Retain the pinned upstream source and these patches with Corresponding Source.
The application's GPL-3.0-only and commercial-distribution policy remain unchanged.

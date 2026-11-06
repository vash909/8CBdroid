# Third-party notices

8CBdroid is an Android development skeleton. It does not yet include the
desktop DSP engine or Hamlib.

- [WSJT-CB](https://github.com/vash909/WSJT-CB), derived from WSJT-X by Joe
  Taylor K1JT and contributors, modified by Vash / 1AT106 / 1XZ001, with
  further credits listed upstream. Its UDP protocol, FT8 parameters and CB
  grammar serve as references at the commit pinned in the documentation.
  Upstream is distributed under GPLv3. This skeleton uses GPL-3.0-only.
  Unmodified CB predicates, FT8 message packing/unpacking and hash routines,
  desktop Radio/DecodedText code and main-window QSO methods, together with
  required legacy packing/include and module dependencies, are
  vendored under `reference/wsjtcb/source/` for host-side tests only. Upstream
  paths, revision and SHA-256 values are recorded in `sources.json` and
  `packing-sources.json` / `qso-sources.json` / `channel-sources.json` / `pcm-sources.json` in the reference directory. These sources and generated
  text fixtures are not in the main APK. The offline native backend is described below.
- Selected WSJT-CB text packing, unpacking and callsign-hash algorithms are
  ported into `core/.../message/CbMessageCodec.kt` under GPL-3.0-only. These
  Kotlin routines are packaged in the app; the original Fortran sources remain
  test-only. The session learning/local-call behavior in `CbMessageSession.kt`
  follows the pinned `packjt77var.f90` / `fillhashvar.f90`, with explicit collision
  and capacity differences. See `docs/CB_MESSAGE_CODEC.md` and
  `docs/CB_HASH_SESSIONS.md` for provenance and limits.
- Gradle Wrapper and Gradle: Apache-2.0. The wrapper scripts and JAR come
  from the official `v8.13.0` tag.
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

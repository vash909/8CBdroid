# Hamlib catalog and native API

**Settings → USB / CAT** includes an explicitly loaded model catalog and a
software-only Dummy check. Neither connects to a physical radio. USB descriptor
permissions remain a separate feature; discovery still opens no serial port.

## Available behavior

Press **Load Hamlib models** to load compiled model IDs, manufacturers, names,
upstream driver status, serial rate ranges and declared read capabilities.
Search by manufacturer, model substring or exact numeric ID. Up to twelve
matches are shown; refine the search to find another model. Tap a row to inspect
its metadata. An upstream “Stable” driver status is not a tested Android setup.
No catalog selection grants USB access or enables frequency writes/PTT.

**Run Dummy check** opens only Hamlib model 1 (Dummy), sets its simulated
frequency to 27.265 MHz, USB mode and PTT off, reads them back and closes it.
The native API accepts neither a model ID nor a device path for this operation.
It uses no microphone, USB connection, network radio or physical PTT.

Catalog/search/selection results survive rotation in `HamlibViewModel`, but are
not durable operating profiles. Controls admit one request at a time. Cancel
invalidates the request and discards late results; another explicit request can
retry. Finite native catalog/Dummy operations finish cooperatively before the
serialized native boundary is released; coroutine cancellation cannot interrupt
arbitrary C code. No hardware I/O exists behind this boundary.

## Source pin and build

- Hamlib **4.7.2**, release revision
  `40f63488fe0bd751b147f48d62fd217bf53713a0`.
- Official release archive URL and SHA-256:
  [`radio/hamlib/source.json`](../radio/hamlib/source.json).
- Built from source into `libeightcbdroid_hamlib.so`, separately from FT8 DSP.
  Android ABIs: `arm64-v8a` and `x86_64`, API 26, NDK 28.2.13676358.
- `scripts/build_hamlib.py` verifies the archive and patch hashes, uses explicit
  toolchains, bounds download size and subprocess time, and builds only the
  static library/dependencies. Binaries, extracted source and build state are
  generated under ignored build directories. No prebuilt Hamlib binary is committed.
- Android Gradle tasks prepare both ABI libraries before CMake configuration.
  GNU Make and `patch` are required in addition to the existing Python/SDK setup.
  No autotools regeneration or system Hamlib package is required.
- Native calls are serialized. The catalog is capped at 4096 entries and model
  IDs must be unique. Read flags describe backend function pointers, not
  successful operations on attached hardware.

The [upstream Android guide](https://github.com/Hamlib/Hamlib/blob/4.7.2/README.android)
uses API 28. This integration keeps API 26 by disabling desktop glob-based
microHAM discovery on Android and applying two narrow, hash-pinned patches:

1. Register the optional Android sensor rotator only when configured present.
   This build omits that rotator and supplies no fake sensor callbacks.
2. Compile the microHAM discovery-thread body only when its `glob` dependency
   exists, matching the existing discovery function guard. Android uses the
   no-glob configuration because `glob()` requires API 28 and desktop `/dev`
   discovery does not provide Android USB permission/access.

No radio command implementation is changed by these patches. Direct libusb,
INDI, Winradio, language bindings and the sensor rotator are excluded. The
catalog therefore describes this particular compiled configuration.

The USB bridge patch from
[JS8Call Android](https://github.com/JS8Call-improved/Android-port/tree/55d18d44100c990f6f7074615036c3aeca2924bb/android/hamlib)
was evaluated for applicability to stable 4.7.2. It is not included or linked:
Android USB transport hooks and their lifecycle still require a later backend.
A patch applying cleanly does not establish a working transport.

## Verify the backend

The normal `bash scripts/check.sh` includes the module's lint and both ABI
builds. For native host checks, install C/C++, CMake, Make and `patch`, then run:

```bash
timeout 900 bash scripts/check_hamlib.sh
```

This runs the same native adapter with the pinned source library and checks
catalog identities/metadata, representative manufacturer coverage, repeated
catalog loads and twenty Dummy open/read/close cycles. It opens no radio ports.

For a phone, install matching app/probe APKs from a successful CI run, then:

```bash
source scripts/env.sh
timeout 45 adb shell am instrument --user 0 -w -r \
  net.vash.ft8cbdroid.engineprobe/net.vash.ft8cbdroid.engineprobe.HamlibProbe
```

Alternatively run `bash scripts/check_hamlib_phone.sh` for a bounded check and
comparison with the report cached on the phone. Expect `hamlib_result=PASS`. The report records version, catalog count, ABI,
Android version, twenty Dummy cycles and three catalog reloads, and explicitly
states `hardware_tested=false`. It is cached privately in the probe app.
Also verify catalog search/details, empty results, reload, Dummy, rotation and
reopening through the real app UI. Evidence belongs in [validation](VALIDATION.md).

## Remaining work

USB serial transport must be connected behind the radio contracts with explicit
permission, bounded I/O and detach/reconnect handling. Real frequency/status
reads, frequency writes, CAT PTT, operating profiles and physical radio/cable
qualification are not implemented by this catalog. CAT and USB audio remain
separate capabilities. Only actual device/radio tests can qualify a configuration.

Hamlib is LGPL-2.1-or-later, with bundled cJSON under MIT. Source pins, local
patches and license assets accompany the build; see
[third-party notices](../THIRD_PARTY_NOTICES.md). The application remains GPL-3.0-only.

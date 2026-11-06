# Architecture

The Android UI depends on `core` and `protocol:wsjtcb`. JVM modules can be tested
without an emulator. Hardware and network work stay outside Compose. No radio
operation or listener starts automatically.

```text
app (Compose, settings/LAN ViewModels, Android lifecycle)
 ├── core (station identity, radio/audio/DSP contracts)
 ├── dsp:ft8 (offline JNI channel engine; no UI session)
 │    └── core
 └── protocol:wsjtcb (wire codec, UDP listener, connection state)
      └── core

native (CMake, pinned ft8_lib channel + JNI, built by dsp:ft8)
engineprobe (separate offline instrumentation APK, uses dsp:ft8)
```

## Station settings

`StationSettingsViewModel` exposes editable and saved identity through StateFlow.
The UI collects it with lifecycle awareness. Preferences DataStore writes the
callsign and locator together on an explicit save request and reports completion
only after the write succeeds. Editing stays disabled until the initial read
succeeds, preventing accidental replacement of unread settings. The application
context delegate provides one DataStore instance per process.

## LAN monitoring

`LanViewModel` owns a `WsjtCbBridge`, validates the IPv4/port inputs and serializes
Start/Stop/Replay requests. `UdpWsjtCbBridge` binds on an IO dispatcher and receives
unicast UDP on a socket-owning worker thread. Its StateFlow exposes listener
phase, selected desktop, latest Status and bounded decode history. Socket close
releases blocking receive without joining on the UI thread. A new bind waits on
IO for the previous receiver to exit; session guards prevent stale publication.

Only a valid heartbeat establishes a peer. IP, ephemeral source port and instance
ID remain pinned until Close or timeout. Outbound messages are heartbeat and
explicit Replay; neither starts radio transmission. Status and Decode parsing
ignore appended extensions. Decode history retains the latest 200 distinct
payloads, ignoring the Live/Replay flag for deduplication; a live decode upgrades
an existing replay. Clear from the desktop, peer loss and Stop clear history.
The local Clear list button changes only phone state. Log parsing remains pending.

The activity stops LAN listening when it leaves the foreground, except during
configuration changes such as rotation. Clearing the ViewModel closes the socket.
There is no background service. Port/filter inputs survive rotation but not process
termination. See [the wire contract and phone test](WSJT_CB_INTEGRATION.md).

## USB discovery and future radio control

`UsbViewModel` owns an application-context `AndroidUsbInventory`. The inventory
uses `UsbManager` and pinned Java serial drivers for descriptor discovery and
explicit permission requests; Compose receives immutable state and callbacks.
A pure Kotlin session tracks device-specific pending/granted/denied access.
No USB port is opened. See [USB setup, scope and phone checks](USB_CAT.md).

`RadioController` defines connection, frequency and PTT operations. Its placeholder
returns Unavailable and advertises no capabilities. Future implementations can use:

1. Hamlib built with Android NDK and JNI. USB access must integrate with file
   descriptors authorized by `UsbManager`; compiling Hamlib alone does not grant
   access to `/dev/ttyUSB*` on Android.
2. Kotlin/Java CAT drivers for explicitly supported radios, using documented
   manufacturer commands and an Android serial transport such as
   [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).

Before integration, pin dependency versions, review licenses and choose the first
radio/cable to test. Attach/detach, device permissions, VID/PID, baud rate, timeouts,
capabilities and PTT release on failure still need implementation. USB audio is
separate from CAT. No universal radio compatibility is claimed.

## Future audio and VOX

`AudioEngine` distinguishes phone microphone/speaker and USB audio. Implement
routing, audio focus, runtime microphone permission and capture/playback through
Android audio APIs or Oboe. The current manifest declares RECORD_AUDIO but the app
does not request it because recording is not implemented.

The DSP profile uses 12 kHz PCM; Android hardware may require resampling from
48 kHz. Define buffers, timestamps, channel layout and PCM representation when
building the real engine. In acoustic operation the radio handles VOX, while the
app will need level calibration and leading/trailing audio timing. Future
background microphone operation requires its own service/permission design.

## Offline FT8 channel engine

`Ft8Engine` now uses typed 77-bit payloads, immutable PCM slots and frame metadata.
`NativeFt8Engine` implements offline encode/decode behind this boundary, with JNI
work outside Compose and one native call at a time. It is packaged with the app
but no operating session starts it. The separate `engineprobe` APK exercises the
same backend with independent WSJT-CB audio fixtures.

The channel layer has no text codec, hash state, absolute UTC or SNR estimator.
`core.message.CbMessageCodec` implements stateless text packing/unpacking outside
Compose, with typed unresolved hashes. `CbMessageSession` owns bounded pending and
committed full calls, typed hash resolution and reset semantics. `HashSessionViewModel`
owns the Messages tab's explicit offline receive simulation and survives rotation;
the cache is never persisted. Saved station changes reset the session; unsaved
drafts do not. This session is separate from stateless TX preview, DSP and LAN.
AutoSeq remains future domain work.
`OfflineFt8Generator` connects message packing to raw PCM with explicit frequency
and level, requiring consent for content changes. `WaveformViewModel` owns one
replaceable job and generated WAV bytes; stale results are discarded on edits.
Its Android document exporter writes a user-selected URI on IO. Compose receives
state/callbacks; no device audio or radio is opened. See [R07](OFFLINE_WAVEFORM.md).
See [the message-codec contract](CB_MESSAGE_CODEC.md) and
[hash-session behavior and desktop differences](CB_HASH_SESSIONS.md). See
[engine decision, bounds and evidence](FT8_ENGINE.md) for source pins, licenses,
Android toolchain/ABI choices and current limitations.

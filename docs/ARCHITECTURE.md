# Architecture

The Android UI depends on `core` and `protocol:wsjtcb`. JVM modules can be tested
without an emulator. Hardware and network work stay outside Compose. No radio
operation or listener starts automatically.

```text
app (Compose, settings/LAN ViewModels, Android lifecycle)
 ├── core (station identity, radio/audio/DSP contracts)
 └── protocol:wsjtcb (wire codec, UDP listener, connection state)
      └── core

native (optional CMake target, outside the current Gradle build)
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
Start/Stop requests. `UdpWsjtCbBridge` binds on an IO dispatcher and receives
unicast UDP on a socket-owning worker thread. Its StateFlow exposes listener
phase, selected desktop and latest Status. Socket close releases blocking receive;
session guards prevent old workers from publishing after a restart.

Only a valid heartbeat establishes a peer. IP, ephemeral source port and instance
ID remain pinned until Close or timeout. The only outbound message is heartbeat:
there are no radio-control commands. Status parsing reads the stable prefix and
ignores later extensions. Decodes and log payloads remain future work.

The activity stops LAN listening when it leaves the foreground, except during
configuration changes such as rotation. Clearing the ViewModel closes the socket.
There is no background service. Port/filter inputs survive rotation but not process
termination. See [the wire contract and phone test](WSJT_CB_INTEGRATION.md).

## Radio control: future Hamlib or Android CAT

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

## Future FT8 engine

`Ft8Engine` is only a contract. A port must verify CB callsigns, hash learning,
type 4 messages, reports and WSJT-CB AutoSeq behavior, as well as CRC, LDPC,
modulation and UTC slot synchronization.

`native/CMakeLists.txt` builds only a C status symbol and is not loaded by the app.
Enable externalNativeBuild and pin NDK/ABIs only when introducing a real engine.
Desktop Fortran source is not directly an Android library; toolchain and porting
work remain to be evaluated.

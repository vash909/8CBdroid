# Architecture

The UI receives state and callbacks. Android device/file access and UDP I/O stay
outside Compose; CB rules live in JVM code. LAN listening, file decoding and WAV
generation are explicit actions. No radio or live audio session starts on launch.
For current capabilities and tested revisions, see [validation](VALIDATION.md).

## Modules and ownership

```text
app (Compose, ViewModels, DataStore, Android USB/document access)
 ├── core (station models, CB codec/hash sessions, WAV/recorded-slot logic,
 │        radio/audio/DSP contracts)
 ├── dsp:ft8 (NativeFt8Engine + JNI bridge)
 │    └── core
 └── protocol:wsjtcb (wire codec, UDP listener and peer state)
      └── core

native (CMake, pinned ft8_lib channel routines + waveform/metadata adapters)
engineprobe (separate offline instrumentation APK, uses the same core/DSP)
reference/wsjtcb (host-only original sources, adapters and expected fixtures)
```

`core` and `protocol:wsjtcb` are Kotlin/JVM modules. They can be tested without
Android. The Android native library is built from source through `dsp:ft8`;
Fortran, Qt and Boost are host reference-test dependencies, not APK runtimes.
Main-app packages exclude the reference corpus. The separate probe packages test
fixtures and needs no audio, USB or network permissions.

## State lifetime

| State | Owner | Lifetime / side effects |
| --- | --- | --- |
| Saved station callsign/locator | DataStore repository | Durable across process restarts; written only by explicit Save. |
| Station draft/load/save status | `StationSettingsViewModel` | Retained across rotation; unread data cannot be overwritten while loading or after a read failure. |
| LAN endpoint, peer/status/history | `LanViewModel` / `WsjtCbBridge` | Inputs survive rotation; not durable. Backgrounding stops the listener and clears peer/history. |
| USB inventory and pending permission | `UsbViewModel` / `AndroidUsbInventory` | Application-context inventory retained by the ViewModel; detach invalidates pending state. Android grants are rechecked. |
| Simulated receive hash session | `HashSessionViewModel` | Explicit pending/commit/reset; rotation retained, process restart resets. Saved callsign changes reset it. |
| Generated waveform/export snapshot | `WaveformViewModel` | Edits invalidate old PCM; rotation retained, process restart discards it. Exported documents remain outside the session. |
| Imported recording/results/UTC | `RecordedSlotViewModel` | One request, isolated hash interpretation; rotation retained, process restart discards it. UTC or saved-call changes invalidate results. |

Only station identity is currently stored as app configuration. Choosing a radio
connection label does not implement or persist an operating profile. Android
system picker history/USB grants have their own OS lifecycle.

## Station settings

Preferences DataStore writes callsign and locator together on Save. StateFlow
reports completion after the write succeeds; initial-read failures expose Retry
and keep editing disabled. The application-context delegate supplies one
DataStore instance per process. Unsaved station drafts do not change RX hash
context. No station setting arms TX or opens hardware.

## LAN monitoring

`LanViewModel` validates IPv4/port inputs and serializes Start/Stop/Replay.
`UdpWsjtCbBridge` binds on IO and receives unicast UDP on a socket-owning worker.
Only a valid heartbeat establishes a peer; IP, ephemeral source port and instance
ID remain pinned until Close or timeout. Status/Decode/Clear from that peer
refresh its lifetime. Appended extensions are ignored after required fields.

Decode history holds 200 distinct payloads; a Live decode upgrades a matching
Replay. Desktop Clear, peer loss and Stop clear history. Local Clear list changes
only phone state. Outbound messages are heartbeat and explicit Replay; there is
no Reply, QSO log import or radio/TX control.

Socket close unblocks receive without joining on the UI thread. A new bind waits
on IO for the old receiver to exit, and session guards reject stale publications.
The activity stops listening on background transitions except rotation; there is
no foreground/background operating service. See [the LAN contract](WSJT_CB_INTEGRATION.md).
LAN SNR/timing are supplied by the desktop, separately from local recorded estimates.

## Offline message and audio-file paths

1. `CbMessageCodec` packs/unpacks selected WSJT-CB text formats and calculates
   callsign hashes. It is stateless; TX previews can render only explicitly
   supplied identities and never warm a receiver cache.
2. `CbMessageSession` stages/commits bounded full-call learning and preserves
   unknown/ambiguous hashes. Its Messages simulator is independent of LAN and
   imported recordings. See [codec](CB_MESSAGE_CODEC.md) and [sessions](CB_HASH_SESSIONS.md).
3. `OfflineFt8Generator` packs a message, requires consent for content changes,
   calls `Ft8Engine.encode` and produces PCM16 WAV bytes. Android's
   `DocumentWaveformExporter` writes a frozen snapshot on IO. Edits/cancellation
   cannot publish stale generation results. See [waveform generation](OFFLINE_WAVEFORM.md).
4. `DocumentRecordedSlotReader` opens a selected URI on IO; the JVM
   `RecordedSlotWav` reader validates size, RIFF chunks, format and full-slot length.
   `RecordedSlotDecoder` combines CRC/LDPC-qualified frames, supplied UTC and a
   fresh CB session with the saved station. Files do not teach other sessions.
   See [recorded slots](RECORDED_SLOTS.md).

`Ft8Engine` accepts immutable 77-bit payloads and normalized 12 kHz mono slots.
`NativeFt8Engine` runs JNI on `Dispatchers.Default`. A process-wide mutex permits
one native encode/decode at a time across instances. The shared JVM
`Ft8WorkGate` admits one active call plus one FIFO waiter and rejects further
requests before dispatcher work/copies; ViewModels still own input snapshots
and stale-result checks. The native channel layer has
no text/hash cache or absolute UTC. It returns relative timing, offset, sync
score and a nullable experimental SNR estimate. The JVM recorded-slot layer
supplies UTC and derives DT. A sync score is not SNR.

Cancellation signals a per-call native flag and is checked between FFT blocks,
candidate operations and SNR symbols. Fast/Standard profiles bound candidates,
iterations and cooperative native deadlines. The unchanged vendor operations
finish before their next check. A blocking document read must still return
before its work ends. Wider/sustained cost and memory-pressure recovery need
further qualification. See [decoder work bounds](DECODER_PROFILES.md). See [engine bounds](FT8_ENGINE.md).

## USB discovery and future radio control

The application-context inventory uses `UsbManager` and pinned Java serial
drivers to enumerate descriptors and request access to recognized interfaces.
A pure JVM session tracks pending/granted/denied access per device. No device or
serial port is opened when access is granted. Compose never owns receivers or
USB handles; inventory receivers are released with their ViewModel.

The selected transport direction follows FT8CN's Java USB-serial approach.
`RadioController` currently has an unavailable implementation with no advertised
capabilities. A real backend still needs the first radio/cable, manufacturer
protocol, baud/port settings, bounded I/O, detach behavior and PTT release tests.
Hamlib/JNI is an alternative requiring separate Android file-descriptor access
and license/build review; it is not included. CAT and USB audio are separate
capabilities. See [USB scope and verification](USB_CAT.md).

## Future microphone, playback and VOX

`AudioEngine` distinguishes phone and USB audio routes, but capture/playback,
focus, routing, levels and resampling are unimplemented. The main manifest
currently declares `RECORD_AUDIO`; the app does not request runtime microphone
permission or record audio. Document import/export uses Android's file picker.

Live reception needs hardware-rate conversion to DSP PCM, timestamped buffers
and complete UTC slots before feeding the existing decoder. Acoustic operation
also needs a tested radio VOX setup, output-level calibration and lead/tail
handling. PTT, scheduling, AutoSeq and background-service lifecycles require
separate implementations and hardware evidence.

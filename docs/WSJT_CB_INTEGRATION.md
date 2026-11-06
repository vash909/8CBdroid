# WSJT-CB LAN integration

Protocol reference: WSJT-CB commit
[`94f776d54946fe43db5e0bb9c966b1595c7b7d97`](https://github.com/vash909/WSJT-CB/tree/94f776d54946fe43db5e0bb9c966b1595c7b7d97).
Review this contract before updating the reference. Host-side CB predicate fixtures
and the WSJT-X comparison inventory are documented in
[WSJT-CB compatibility](WSJT_CB_COMPATIBILITY.md); they do not add local decoding.

## Implemented LAN behavior

- IPv4 unicast listener with explicit Start/Stop controls and an optional desktop IPv4 filter.
- Heartbeat discovery, schema 2/3 negotiation and periodic heartbeat replies every 15 seconds.
- One desktop at a time, pinned by source IP, source port and instance ID.
- Display of the desktop's dial frequency, mode, DX callsign/report and TX/decoding flags.
- Display up to 200 distinct live/replayed decodes with UTC, SNR, delta time,
  frequency offset, raw mode, low-confidence and off-air indicators.
- Explicit Replay to the selected desktop and local Clear list controls.
- Desktop Clear empties phone history. Stop, Close and peer timeout also clear it.
- Peer release after 45 seconds without a valid heartbeat/status/decode/clear, or on desktop Close.
- Recovery from malformed datagrams, unknown message types and bind failures.
- Foreground-only operation: stop when the app goes into the background; retain the listener across rotation.

ADIF/log parsing, radio-control commands, audio streaming, multicast and
automatic network scanning are not implemented. LAN options
are retained across rotation, but are not persisted after process termination.

## Desktop setup and phone test

1. Connect the PC and phone to the same LAN. Find the phone's Wi-Fi IPv4 address
   in Android's network settings. Client isolation must allow traffic between them.
2. In the app's **LAN** tab, select the listening port, normally **2237**.
   Optionally enter the PC's IPv4 address to restrict the accepted sender.
3. Tap **Start listening**. The app should show that it is waiting for a desktop.
4. In WSJT-CB's UDP settings, set the UDP server to the **phone's** IPv4 address
   and the same listening port. The default desktop server `127.0.0.1` addresses
   the PC itself and cannot reach the phone.
5. Wait for the next heartbeat (normally within 15 seconds). Check the desktop
   endpoint, instance ID, version and negotiated schema in the app.
6. Change a harmless desktop setting, such as the DX call. Check that a new
   Status packet updates the app. Receiving status does not require sending
   radio or TX commands from the phone.
7. Wait for a desktop decode. Check its message, UTC time, SNR, delta time,
   offset and flags under **LAN band activity**, also available in **Station**.
8. Enable **Accept UDP requests** in WSJT-CB. Tap **Clear list**, then
   **Request replay**: desktop history should arrive marked **Replay**. Replay
   is never automatic. Without clearing, identical replay payloads are deduplicated
   and existing Live entries retain their label. Clear list affects only this phone.
9. Clear desktop band activity and check that its Clear packet empties phone
   history. Tap **Stop** and start repeatedly. Close WSJT-CB to test Close
   handling; interrupt traffic to test the 45-second timeout. History must clear.
10. Send the app into the background and return: the listener must remain
    stopped until explicitly started again. Rotation should retain it.

For a simulated desktop, run from the PC:

```bash
python3 scripts/wsjtcb_lan_probe.py --host PHONE_WIFI_IPV4
```

The probe sends simulated heartbeat/status and live decodes, responds to explicit
Replay, then sends desktop Close. It never controls a radio. While it runs, expect
FT8 at 27.265000 MHz, DX `26AT101` and sample CQ messages. For more time to test
Clear list and Replay, add `--count 30`. The app returns to waiting and clears
history when the probe sends Close. This checks the app's
LAN path, not interoperability with a real WSJT-CB installation.

## Socket direction and ownership

The desktop's `MessageClient` binds an **ephemeral source port** and sends to
its configured UDP server. The phone is that server. Replies go to the
received desktop endpoint, not to a presumed desktop port 2237, and carry the
received desktop instance ID. A second endpoint or instance is ignored until
the selected peer closes or expires.

The listener opens a socket only on an explicit Start request. I/O runs off
the main thread. Socket closure unblocks receive; session identity guards
prevent an old worker from overwriting a restarted listener's state. Start
and Stop/Replay requests are serialized in the ViewModel, including a background
transition while binding. Restart waits off the UI thread until the previous
receiver exits, avoiding a same-port bind race after socket closure. Clearing the ViewModel closes its owned socket.
Stopping never sends a Close command to the desktop: in that direction it
could request termination of WSJT-CB.

## Wire format

Big endian: `uint32 magic=0xadbccbda`, `uint32 schema`, `uint32 type`, followed
by the instance ID as a Qt `QByteArray`: a `uint32` byte length then UTF-8
bytes. Length zero and `0xffffffff` represent empty and null strings.

The initial schema is 2 (`Qt_5_2`), with maximum 3 (`Qt_5_4`). Schema 1 is
unsupported. A heartbeat advertises its maximum schema, version and revision;
legacy header-only heartbeats imply maximum schema 2. The listener replies
with the highest supported common schema, capped at 3. The pinned desktop
uses the reply header to upgrade from its initial schema 2.

Status parsing reads the stable prefix: unsigned 64-bit dial frequency,
mode, DX call, report, TX mode, TX enabled, transmitting and decoding flags.
Later fields are ignored, permitting appended extensions. Malformed required
fields are rejected without stopping the socket loop. Only heartbeat establishes
a peer; Status, Decode and Clear require that peer and refresh its lifetime.

Decode (type 2) reads New, QTime, signed 32-bit SNR, delta time, unsigned
32-bit frequency offset, mode, message, low-confidence and off-air flags. Qt
serializes delta time as a 64-bit IEEE double for schemas 2 and 3. QTime is
milliseconds since UTC midnight; `0xffffffff` represents unavailable time.
Invalid times, non-finite delta times and malformed required fields are rejected.
Missing legacy confidence/off-air flags default to false. Text and raw mode
markers are preserved as received; duplicate payloads differing only in New are
shown once, with Live taking precedence. History is in receipt order, with the
latest 200 distinct entries retained, displayed newest first.

Desktop Clear (type 3) empties decode history. Explicit Replay (type 7) is a
header-only request using the selected peer ID, negotiated schema and ephemeral
source endpoint. It requires desktop **Accept UDP requests**; there is no
acknowledgement or guarantee of a response. No Reply/QSO/TX command is sent.
Log parsing still needs the schema-specific QDateTime representation.

## Android network access

The current target SDK is 36, using the existing `INTERNET` manifest permission.
Before changing the target SDK, review the applicable local-network access
requirements in the [Android documentation](https://developer.android.com/privacy-and-security/local-network-permission).
No foreground service or background-listening capability is claimed here.

## Verification and upstream sources

JVM tests use independent binary fixtures and real loopback UDP sockets for
source-port replies, legacy schema fallback, status, malformed packets, peer
pinning, source filtering, Close, timeout, periodic heartbeat and repeated port
reuse under traffic. Decode fixtures cover Qt numeric types, optional flags,
malformed payloads and schema 2/3. Tests also cover deduplication, the 200-entry
limit, desktop/local Clear and the exact Replay endpoint/header. ViewModel tests
cover input validation, failed binds, replay failures, duplicate requests,
foreground stop ordering and disposal. Android device/real-desktop interoperability remains a manual check.

- [NetworkMessage.hpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/NetworkMessage.hpp): wire contract and message types.
- [NetworkMessage.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/NetworkMessage.cpp): Qt serialization.
- [MessageClient.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/MessageClient.cpp): ephemeral socket, schema upgrade and Status sender.
- [Configuration.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Configuration.cpp): desktop server defaults.

Local offline FT8 now has separate [message/hash](CB_MESSAGE_CODEC.md),
[waveform](OFFLINE_WAVEFORM.md) and [recorded-slot](RECORDED_SLOTS.md) paths.
They do not consume LAN audio or share learned-call sessions. LAN SNR/DT are
reported by the desktop; recorded SNR/DT come from the local backend and supplied
UTC. Full live/RF and QSO interoperability remain unverified; see
[validation](VALIDATION.md).

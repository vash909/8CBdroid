# WSJT-CB LAN integration

Protocol reference: WSJT-CB commit
[`94f776d54946fe43db5e0bb9c966b1595c7b7d97`](https://github.com/vash909/WSJT-CB/tree/94f776d54946fe43db5e0bb9c966b1595c7b7d97).
Review this contract before updating the reference.

## Implemented in the second feature PR

- IPv4 unicast listener with explicit Start/Stop controls and an optional desktop IPv4 filter.
- Heartbeat discovery, schema 2/3 negotiation and periodic heartbeat replies every 15 seconds.
- One desktop at a time, pinned by source IP, source port and instance ID.
- Display of the desktop's dial frequency, mode, DX callsign/report and TX/decoding flags.
- Peer release after 45 seconds without a valid heartbeat/status, or on desktop Close.
- Recovery from malformed datagrams, unknown message types and bind failures.
- Foreground-only operation: stop when the app goes into the background; retain the listener across rotation.

Decodes, ADIF/log parsing, Replay, radio commands, audio streaming, multicast
and automatic network scanning are not implemented in this PR. LAN options
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
7. Tap **Stop** and start again. Close WSJT-CB to test Close handling. Interrupt
   its network traffic to test the 45-second inactivity timeout.
8. Send the app into the background and return: the listener must remain
   stopped until explicitly started again. Rotation should retain it.

For a simulated desktop, run from the PC:

```bash
python3 scripts/wsjtcb_lan_probe.py --host PHONE_WIFI_IPV4
```

The probe sends only simulated heartbeat/status and a desktop Close. It never
controls a radio. While it runs, expect FT8 at 27.265000 MHz and DX `26AT101`.
The app returns to waiting when the probe sends Close. This checks the app's
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
and Stop requests are serialized in the ViewModel, including a background
transition while binding. Clearing the ViewModel closes its owned socket.
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
fields are rejected without stopping the socket loop. Other message types
do not establish or refresh a peer. Status requires a preceding heartbeat.

Future Decode parsing must treat Qt floats as serialized 64-bit IEEE doubles.
`QTime` is milliseconds since midnight. Log parsing must account for the
schema-specific `QDateTime` representation. These payloads are outside this PR.

## Android network access

The current target SDK is 36, using the existing `INTERNET` manifest permission.
Before raising the target to 37+, implement the local-network runtime permission
requirements described in the [Android documentation](https://developer.android.com/privacy-and-security/local-network-permission).
No foreground service or background-listening capability is claimed here.

## Verification and upstream sources

JVM tests use independent binary fixtures and real loopback UDP sockets for
source-port replies, legacy schema fallback, status, malformed packets, peer
pinning, source filtering, Close, timeout, periodic heartbeat and port reuse.
ViewModel tests cover input validation, failed binds, foreground stop ordering
and disposal. Android device/real-desktop interoperability remains a manual check.

- [NetworkMessage.hpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/NetworkMessage.hpp): wire contract and message types.
- [NetworkMessage.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/NetworkMessage.cpp): Qt serialization.
- [MessageClient.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Network/MessageClient.cpp): ephemeral socket, schema upgrade and Status sender.
- [Configuration.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Configuration.cpp): desktop server defaults.

FT8 engine compatibility still requires separate tests of CB callsigns, hashes,
type 4 packing and CB QSO sequences. The local FT8 engine is not integrated.

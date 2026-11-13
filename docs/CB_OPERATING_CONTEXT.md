# CB countries and receive filters

Operate adds read-only country/division labels to local and WSJT-CB LAN received
messages. A recognized sender shows its complete callsign, numeric CB division
and country/territory, for example `From 26AT101 · CB 026 · England`. Selecting
a local peer also displays that peer's country. This is a division label, not
the station's measured location. Locator and distance displays are not provided.

If the sender cannot be identified, no sender/country line is shown. In
particular, a destination-only report such as `1AT106 -07` identifies the
recipient, not the sender. Unresolved or ambiguous hashes never identify an
invented station. A known sender with an unmapped division displays
`Country unknown`, for example division `999`.

## Display filters

- **All** shows every received row, including unknown identities and free text.
- **CQ** shows structured CQ messages. An arbitrary free-text string that happens
  to contain `CQ` is not treated as a structured call.
- **To me** shows directed messages whose identified recipient matches the saved
  station callsign, including destination-only reports/finals. An unresolved
  destination cannot match this filter.
- **Worked** shows identified senders in the [saved QSO log](LOGBOOK.md) for the
  current station callsign. Pending reviews, received rows, completed speaker
  audio and a selected peer alone do not establish worked history. The filter
  is unavailable until the log loads or while a log error needs attention.

The received count shows matching/total rows when filtered. The filter survives
Activity recreation; it is not a saved station/profile preference. Sent audio
is unaffected. Filters never remove backend rows, select a peer, learn hashes,
change QSO advice/AutoSeq input or arm transmission. Local frequency/peer actions
remain explicit; LAN stays read-only with its source/confidence/replay labels.

LAN packets contain desktop text rather than typed local codec identities. The
display accepts only lossless supported encodings. An explicit `<callsign>` may
identify its matching hash for that row's display; `<...>` stays unidentified.
This display interpretation supplies no local QSO facts or hash-session learning.

## Reference and reproduction

The offline table contains the **350** CB division entries embedded in
`logbook/AD1CCty.cpp` at the existing WSJT-CB pin
`94f776d54946fe43db5e0bb9c966b1595c7b7d97`. It does not use the amateur `cty.dat`
database. Historical desktop names and missing divisions are preserved; display
translations are `Sardegna` → `Sardinia` and `Palestina` → `Palestine`.
Updating geography requires an explicit reference/data change.

`reference/wsjtcb/context-sources.json` records source paths and SHA-256 hashes.
The original GPL country functions and `Radio::cb_country_prefix` generate
**1,249 differential cases**, covering numeric, base and slash calls, leading
zeros, invalid inputs and missing divisions. The runtime table is packaged in
the core JAR/APK; the C++ sources and test fixture are host-only. See
[third-party notices](../THIRD_PARTY_NOTICES.md).

With the C++/Qt reference environment from [development](DEVELOPMENT.md):

```bash
python3 scripts/wsjtcb_context.py --check
python3 scripts/wsjtcb_context.py --check --optimization O2
```

`scripts/check.sh` also verifies source hashes/runtime data without Qt and runs
the JVM differential and message/filter tests.

For a short physical PC-speaker → phone-microphone regression, install matching
app/device-check APKs, select the Phone profile, save station `1AT106`, configure
a working NTP host, grant microphone permission and leave the phone unlocked
beside the speakers. On a synchronized Linux host with the reference Fortran/Qt
compiler environment:

```bash
timeout 360 python3 scripts/operating_context_checks.py --output /private/path/context-check
```

The helper sends three original desktop-generated waveforms and checks actual
received countries, CQ/To-me filters, hidden unknown sender, explicit peer
selection, Activity recreation and RX recovery/Stop. A short real UDP exchange
with a simulated desktop also checks LAN country/filter presentation and replay,
off-air and low-confidence labels; both devices must share a reachable LAN.
It does not change system
audio levels. Output includes private device/test evidence; do not commit it.
This checks software/acoustic behavior, not radio TX, RF or completed contacts.

# CB callsign hash sessions (R06)

`core.message.CbMessageSession` adds learned-call resolution to the stateless
R05 codec. The **Messages** tab exposes an offline receiver simulation. It does
not receive audio, read LAN decodes, authorize a QSO peer or enable transmission.

## Session contract

- One owner serializes access to each session. No global state or database is used.
- `unpack(payload)` first uses the supported stateless unpacker. Only successful
  structured messages stage full calls from their call fields. Free text,
  locators, reports, unresolved hashes and rejected frames never teach the cache.
- `learnCall(call)` explicitly stages a full identity supplied by a trusted caller.
  It normalizes ASCII case/outer spaces and accepts 3–11 call-alphabet characters
  with at least one letter and one digit. This is hash-storage validation, not
  proof of CB grammar, encodability or identity. Brackets/placeholders are invalid.
- `commitPending()` makes a batch visible to subsequent lookups and receives.
  Pending calls cannot resolve the frame that staged them. Duplicate calls do
  not consume more storage. Committing does not rewrite previous result snapshots.
- `reset(stationCall)` clears all learned/pending identities and overflow state.
  Changing the saved station starts a new session. Invalid reset input is rejected
  before clearing any state. Local identity is context, not a learned peer.
- Default bounds are 1,000 learned/pending distinct calls combined and 200 pending
  calls per batch. No eviction silently removes a collision candidate. If either
  limit is exceeded, all future hash lookups remain unresolved with
  `CAPACITY_REACHED` until reset, even after a commit.

Hash results keep their original width/value. `ResolvedCall` means exactly one
known candidate; an unobserved collision is still possible. `AmbiguousCall`
retains all known candidates and renders `<...>`. `UnresolvedCall` retains an
unknown/overflow reason and also renders `<...>`. No result authorizes AutoSeq.
Types 1/2 carry 22-bit hashes and type 4 carries 12-bit hashes; direct lookup also
supports 10 bits for reference compatibility. Other message subtypes remain
unsupported. Recorded-slot decoding supplies CRC/LDPC-qualified frames before
using its isolated session. The Messages simulator is a separate inspection
path; accepting a 77-bit container alone does not establish signal integrity.
Future live RX must preserve that boundary.

## Desktop reference and deliberate differences

Reference: WSJT-CB `94f776d54946fe43db5e0bb9c966b1595c7b7d97`, unchanged
`packjt77var.f90` (`save_hash_callvar`, `hash10/12/22var`, `unpack77var`,
`add_call_to_recent_callsvar`) and `fillhashvar.f90` in `reference/wsjtcb/source`.

The session preserves pending/committed learning and both type 4 orientations.
An explicit local station resolves a leading type 4 destination hash, but does
not resolve a trailing source hash merely because it matches the local call.
Local hash22 is available in both standard-message fields. A local call is not
inserted into the ordinary 10/12-bit learned table.

The desktop replaces a colliding identity with the last learned call. Android
deliberately retains ambiguity for hash10/12 (`1AT0002`, `1AT0050`) and hash22
(`5AT179`, `10AT107`), including collisions with local identity when applicable.
It never gives local identity precedence over a conflicting learned candidate.
Capacity exhaustion also fails closed instead of evicting. Rejected frames cannot
partially teach the session, even where desktop internals stage calls before a
later validation failure. This is supported message/hash behavior, not a port of
every desktop acceptance filter, worker table, recent-call list or TX hash table.

`scripts/wsjtcb_codec.py` exports all **96** original RX scenarios into
`core/src/test/resources/wsjtcb/hash-sessions.tsv`, with source revision and
packing JSON checksum. The test-only Kotlin adapter runs on JVM and in the
separate probe APK. It compares desktop success, text and lookup results; only
the three named collision scenarios may deliberately differ, requiring the exact
two candidates and preserving the desktop's selected call as one of them.
The original Fortran and desktop JSON remain unchanged. Other tests cover reset,
session isolation, invalid input, rejected-frame learning and bounded storage.

```bash
source scripts/env.sh
bash scripts/controlled.sh 60 python3 scripts/wsjtcb_codec.py --check
bash scripts/controlled.sh 600 bash scripts/check.sh
ADB_LIBUSB=1 bash scripts/controlled.sh 120 bash scripts/probe_phone.sh
```

The probe reports `hash_session_scenarios=96` alongside the existing 154 text
vectors and four desktop WAVs. Device execution is reported separately in the PR;
building the probe alone does not establish a phone pass. The command above
installs the local probe; [CI deployment](DEVELOPMENT.md#deploy-and-test-on-a-phone)
uses verified CI artifacts. Accepted results are recorded in [validation](VALIDATION.md).

## Phone verification

1. Open **Messages**. RX simulation is separate from the unchanged TX preview.
2. Tap **Reset learned calls**. Preview `CQ 26AT101` and tap **Simulate receive**.
   Pending calls should increase; learned calls should remain zero.
3. Tap **Commit learned calls**. Preview `<26AT101> 161XZ085`, then simulate receive.
   The last receive should show `<26AT101> 161XZ085` and a known-candidate notice.
4. Reset and receive the same directed payload again. It should show
   `<...> 161XZ085` (use a saved local station other than `26AT101`).
5. Simulate `CQ 1AT0002`, commit, then `CQ 1AT0050`, commit. Simulate
   `<1AT0002> 26AT101`. It should show `<...> 26AT101` and list both ambiguous
   candidates. The stateless TX preview above still shows the explicit input.
6. Rotate and switch tabs: the session survives through its activity ViewModel.
   Force-stop/reopen: learned calls are gone; saved station settings remain.
7. Change and save the station callsign: learned calls and the last result reset.
   Editing an unsaved station draft does not change the session.

All labels, warnings and errors remain English. No radio/audio permissions or
live networking are required for these checks.

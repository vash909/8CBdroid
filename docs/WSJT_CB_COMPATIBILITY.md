# WSJT-CB compatibility inventory

## Scope and revisions

R03a establishes a real desktop predicate reference and documents the relevant
CB differences. R03b adds production payload and hash-state fixtures; R03c adds
the real parser, generated messages and receive-side AutoSeq traces. These add
no local decoder, encoder, hash cache or AutoSeq behavior to the app.
The inventory is based on inspected code, not only release notes. Every listed
source file is pinned with a checksum in
[`sources.json`](../reference/wsjtcb/sources.json) and the host packing sources in
[`packing-sources.json`](../reference/wsjtcb/packing-sources.json) /
[`qso-sources.json`](../reference/wsjtcb/qso-sources.json).

| Label | Repository / revision | Role |
| --- | --- | --- |
| `cb` | WSJT-CB `94f776d54946fe43db5e0bb9c966b1595c7b7d97` | Required operating reference; same pin as the LAN contract. |
| `x3` | WSJT-X v3.0.0, `ab976b1b4b72a96aaa3259591f68ad772af7d7f9` | Fixed comparison release for Radio and desktop QSO handling. |
| `x32` | WSJT-X v3.2.0-rc1, `567ad29ce6abf3d4a44f181cdbc7ceba0d73e5f4` | Fixed comparison release for the later decoder backport. |

The initial WSJT-CB source snapshot is **WSJT-X 3.0.0-rc1**. This is now verified
against the official source release, in addition to the owner's identification.
The release named **WSJT-CB V1.0** uses tag `WSJT-CB`, commit
[`615d079949f5d4c50df9db05fbdc12ccb5c9df99`](https://github.com/vash909/WSJT-CB/commit/615d079949f5d4c50df9db05fbdc12ccb5c9df99).
The first import is the parentless commit
[`51b01eec2b0fde38c36c27fb5d3a341c4771082a`](https://github.com/vash909/WSJT-CB/commit/51b01eec2b0fde38c36c27fb5d3a341c4771082a).

## Exact initial source pin

The official [RC1 source release](https://sourceforge.net/projects/wsjt/files/wsjtx-3.0.0-rc1/wsjtx-3.0.0-rc1.tgz/download)
contains the real source inside `wsjtx-3.0.0-rc1/src/wsjtx.tgz`:

- Download archive SHA-256: `faeaf3c1166ae4e5a68502823d4b080d41d221d8ab19e1d1c374e2f7749bfe96`.
- Nested source SHA-256: `0391b58d66940511150fe5ce9f87695985a0d399733a8377b3b22634a6b77b83`.

The [comparison report](../reference/wsjtcb/initial-snapshot.json) records 1,874
byte-identical files out of 1,891 common files at the initial CB import. The 17
different common files consist of eight source/build files and nine binary
resources; these differences are retained in the report, not automatically
classified as CB changes. The V1.0 release has 1,863 byte-identical files out of
1,892 common files. Added/removed paths are also reported. CRLF normalization does
not change either match count. The comparison checks regular-file contents, not
Git modes, symlink targets or an entire commit identity.

The initial import and V1.0 retain RC1's CMake version and the `250915` source
identifier. That identifier is a date, not a Git SHA. The imported tree has no
upstream Git parent; its qmake revision headers are placeholders, and the release
archive has no Git commit in its archive metadata. No unique upstream Git commit
has been established by this investigation. Use the verified archive hashes as
the exact RC1 source pin rather than substituting a nearby release-preparation
commit. [Reproduce the archive/import comparison](../reference/wsjtcb/README.md#verify-the-original-rc1-snapshot).

The GitHub comparison tags above were resolved to commits. `x3` is the later
v3.0.0 release, not the original rc1 snapshot; `x32` describes later decoder
provenance. They remain useful comparisons, but neither replaces the verified
RC1 archive. A textual diff against one release is evidence for inspected
differences, not proof that every changed line is CB-specific.

Use [the reproducible source audit](../reference/wsjtcb/README.md#audit-the-comparison-sources)
to verify hashes and obtain text diffs without CRLF noise. The manifest also pins
both packing files inspected in the CB tree. Their module layout and size differ
from upstream `lib/77bit/packjt77.f90`; do not attribute that entire diff to a new
CB wire format. R03b now records results from both production packing entry points
for the same inputs; it does not establish a new CB waveform or channel code.

## Inspected behavior and Android obligations

All CB source links below point to the pinned reference. The implementation
status column distinguishes today's test coverage from future runtime work.

| Area and source | Inspected difference / required behavior | Evidence and remaining work |
| --- | --- | --- |
| Complete calls: [Radio.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Radio.cpp#L45) and [cb_callsigns.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/cb_callsigns.f90) | CB adds numeric-prefix forms and a complete-call predicate absent from the inspected upstream Radio files. Preserve complete versus partial identity. | R03a: 240 ASCII desktop results compared with the existing Kotlin validator. Qt/Unicode normalization is not executed by this harness. |
| Partial calls and prefix lookup: [Radio.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Radio.cpp#L67) | Prefix lookup can accept an incomplete CB stem; `is_cb_callsign` therefore has a broader purpose than `is_complete_cb_callsign`. | Never use the broader predicate as evidence of a complete QSO peer. Kotlin currently validates complete station identity only. Prefix/country behavior remains R24 work. |
| Type 4 text admission: [cb_callsigns.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/cb_callsigns.f90#L64) | CB-specific recognition covers CQ with a complete CB call, or one full call plus one hashed call in either orientation, with the supported final tokens. | R03a: 75 generated predicate cases. R05 applies CB complete-call checks in its supported binary codec paths; R06 adds separate session learning. General QSO admission remains later work. |
| Type 4 unpack checks: [packjt77var.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/packjt77var.f90#L688) | CB permits valid numeric-prefix calls through relevant amateur plausibility checks and handles the trailing-hash orientation with the CB predicate. | R03b: real pack77/pack77var payloads and unpack77var RX results in both orientations, including successful packing followed by failed unpacking. |
| Hash learning: [fillhashvar.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/fillhashvar.f90) | Received complete calls are queued per worker before merging into RX hash tables. Local identity has orientation-specific lookup behavior; collisions can replace an identity. | R03b: 10/12/22-bit vectors, cold/pending/committed state, CQ learning and real collisions. A resolved hash is not proof of a unique QSO peer. R06 adds a bounded Android session with explicit commit/reset and collision ambiguity; see [the session guide](CB_HASH_SESSIONS.md). |
| False-decode filtering: [chkfalse8var.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/chkfalse8var.f90#L1) | After line-ending normalization, the inspected file differs from both comparison versions by the CB module import and an early return for qualifying type 4 messages. This return does not clear a pre-existing failure flag. | Preserve integrity checks before this stage. The predicate fixture cannot establish CRC correctness; upstream regression tests exercise that guard, with Android engine checks still pending. |
| AutoSeq admission: [has_complete_cb_peer](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L7479) | The CB fork adds peer extraction/fallback and requires a complete CB peer when answering CQ. That helper is absent from the inspected upstream main windows. | R03c executes the original methods: complete CB CQ answers progress; unknown/partial/amateur peers do not start a CB QSO. No app AutoSeq exists. |
| AutoSeq progression: [auto_sequence](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L7511) | CB adds addressed non-standard messages and report/final recognition using local identity plus selected-partner context. Existing state, frequency tolerances and stop decisions still matter. | R03c records accepted/ignored/stop outcomes, all six progress states, duplicate/out-of-order events, completed context and frequency boundaries. Known unrelated-final behavior requires explicit Android policy. |
| Parsed message recovery: [processMessage](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L9004) | CB adds free-text/addressed-message fallbacks, selected-peer recovery and report parsing. | R03c runs the actual processMessage and DecodedText implementations. It records selected-peer recovery and differences between malformed-report manual selection and AutoSeq admission. |
| TX message generation: [genStdMsgs](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L9778) | Two non-standard CB calls use a dedicated exchange branch; reports and finals can carry only the destination call. | R03c records the original six generated messages and 72 unique production payloads, including report sign/zero padding, numeric/slash calls and mixed-call controls. |
| Decoder concurrency | The fork records upstream multithread-decoder fixes separately from CB acceptance changes. | Use the per-fix commits in [the upstream test notes](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/tests/ft8/README.md); benchmark whichever Android engine is chosen. These are not new CB callsign rules. |

## Concrete cases to retain

The generated corpus includes valid short/long/numeric/slash forms, leading zeros,
invalid lengths, missing units and embedded punctuation. For example, `1AT1000`
and `999ZZ/ZZ` pass complete-call validation, while `26AT1000`, `1AT` and `<...>`
do not. The corpus explicitly exercises zero-valued prefixes/units according to
the desktop grammar; it does not add a new country-allocation policy.

A qualifying type 4 message may contain `<...>` while still having one complete
CB call. That says only that the text matches the desktop's CB plausibility
exception. The caller, learned-hash state, CRC result and QSO state remain separate
requirements. The 75 predicate cases include reports that are not type 4 messages;
rejection there does not mean the same text cannot use another FT8 message type.

For the two-non-standard-CB-call branch, the inspected desktop generates an
initial hashed/full-call exchange, then destination-only signed reports,
roger-reports and finals. R03b records these messages' payloads and actual decoded
text; R03c verifies generated exchanges and receive-side QSO decisions. Do not apply a
generic template to every message or mode. The current app only
validates station identity and displays remote decodes unchanged.

R03b also records lossy encodings: `1AT106 26AT101 -10` becomes free text
`1AT106 26AT10`, and `<1AT106> 26AT101 -10` loses its report in type 4.
The [packing/hash guide](../reference/wsjtcb/PACKING.md) documents the corpus,
cache operations and collision cases. Future TX validation must compare the
actual unpacked result, rather than relying on pack success or call grammar.

## Verification and limits

Run the commands in [the reference README](../reference/wsjtcb/README.md#reproduce).
R03a regenerates 315 results by compiling the original Fortran module and checks
the committed data in CI. The normal JVM suite compares 240 call results with the
production Kotlin validator. R03b adds 66 defined payload pairs, 15 hash vectors and 79 RX
scenarios. R03c adds 129 parser/generation/AutoSeq scenarios and 72 generated-message
payloads. Forty-one Python tests now cover native behavior, source/result integrity,
compiler diagnostics, archive checksum rejection, duplicate paths and byte versus
text comparison. CI regenerates packing/hash and QSO results at both O0 and O2. R04 moves one
undefined TX placeholder into an explicit raw RX scenario; see
[the packing reference correction](../reference/wsjtcb/PACKING.md#undefined-tx-placeholder-discovered-during-r04).

R05 extends this corpus to 82 TX cases and 96 RX scenarios and compares 154
message cases against the Kotlin codec. See [the codec guide](CB_MESSAGE_CODEC.md)
for supported types, explicit rejections and limits.

The source audit checks all 37 distinct files from four manifests at immutable
revisions. The host job compiles predicates, packing dependencies and selected
desktop QSO methods with Qt Core/Network, without the GUI or full decoder. The upstream
[test_cb_autoseq harness](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/tests/ft8/test_cb_autoseq.cpp)
uses real admission/AutoSeq methods with substitute controls and `processMessage`.
R03c goes further by executing the actual processMessage/generation methods and
relevant control handlers, with documented substitutes for presentation and
external effects. See [the QSO trace guide](../reference/wsjtcb/QSO.md).

R03c completes the documented message-level reference corpus, including a
two-station generated-text exchange. R03c is merged as PR #18. R04 adds 116
channel-codeword/tone matches and four independent WAV decodes on ARM64; see
[the engine decision and evidence](FT8_ENGINE.md). Live audio, corrupt-frame
qualification, radio interoperability and the full TX lifecycle remain later gates.
Recorded desktop outcomes include unrelated finals advancing a QSO, malformed
manual reports and out-of-order regressions; R21/R22 must qualify those cases and
document deliberate Android differences before enabling automation.

No application behavior changes in R03a/R03b/R03c. A phone smoke test may confirm that
saved settings, LAN and USB discovery remain available, but cannot validate these
reference algorithms or claim local FT8 support.

# WSJT-CB compatibility inventory

## Scope and revisions

R03a establishes a real desktop predicate reference and documents the relevant
CB differences. It adds no local decoder, encoder or AutoSeq behavior to the app.
The inventory is based on inspected code, not only release notes. Every listed
source file is pinned with a checksum in
[`sources.json`](../reference/wsjtcb/sources.json).

| Label | Repository / revision | Role |
| --- | --- | --- |
| `cb` | WSJT-CB `94f776d54946fe43db5e0bb9c966b1595c7b7d97` | Required operating reference; same pin as the LAN contract. |
| `x3` | WSJT-X v3.0.0, `ab976b1b4b72a96aaa3259591f68ad772af7d7f9` | Fixed comparison release for Radio and desktop QSO handling. |
| `x32` | WSJT-X v3.2.0-rc1, `567ad29ce6abf3d4a44f181cdbc7ceba0d73e5f4` | Fixed comparison release for the later decoder backport. |

The comparison tags were resolved to commits. Neither tag is claimed to be the
exact ancestor of the whole CB tree. The local fork history starts from an
imported source snapshot, whose initial CMake version declares 3.0.0 RC1; that
alone does not establish the upstream commit. Later decoder changes have their
own upstream provenance. Exact initial-snapshot ancestry remains unresolved.
A textual diff against one release is evidence for inspected differences, not
proof that every changed line is CB-specific.

Use [the reproducible source audit](../reference/wsjtcb/README.md#audit-the-comparison-sources)
to verify hashes and obtain text diffs without CRLF noise. The manifest also pins
both packing files inspected in the CB tree. Their module layout and size differ
from upstream `lib/77bit/packjt77.f90`; do not attribute that entire diff to a new
CB wire format. Packing must be compared at routine/bit-result level next.

## Inspected behavior and Android obligations

All CB source links below point to the pinned reference. The implementation
status column distinguishes today's test coverage from future runtime work.

| Area and source | Inspected difference / required behavior | Evidence and remaining work |
| --- | --- | --- |
| Complete calls: [Radio.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Radio.cpp#L45) and [cb_callsigns.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/cb_callsigns.f90) | CB adds numeric-prefix forms and a complete-call predicate absent from the inspected upstream Radio files. Preserve complete versus partial identity. | R03a: 240 ASCII desktop results compared with the existing Kotlin validator. Qt/Unicode normalization is not executed by this harness. |
| Partial calls and prefix lookup: [Radio.cpp](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/Radio.cpp#L67) | Prefix lookup can accept an incomplete CB stem; `is_cb_callsign` therefore has a broader purpose than `is_complete_cb_callsign`. | Never use the broader predicate as evidence of a complete QSO peer. Kotlin currently validates complete station identity only. Prefix/country behavior remains R24 work. |
| Type 4 text admission: [cb_callsigns.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/cb_callsigns.f90#L64) | CB-specific recognition covers CQ with a complete CB call, or one full call plus one hashed call in either orientation, with the supported final tokens. | R03a: 75 generated predicate cases. This is neither bit packing nor hash resolution, and no Android type 4 predicate is added yet. |
| Type 4 unpack checks: [packjt77var.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/packjt77var.f90#L688) | CB permits valid numeric-prefix calls through relevant amateur plausibility checks and handles the trailing-hash orientation with the CB predicate. | Packing/unpacking must be tested with the real production routines and both orientations. Source inspection is recorded; generated bit fixtures remain R03b. |
| False-decode filtering: [chkfalse8var.f90](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/lib/ft8var/chkfalse8var.f90#L1) | After line-ending normalization, the inspected file differs from both comparison versions by the CB module import and an early return for qualifying type 4 messages. This return does not clear a pre-existing failure flag. | Preserve integrity checks before this stage. The predicate fixture cannot establish CRC correctness; upstream regression tests exercise that guard, with Android engine checks still pending. |
| AutoSeq admission: [has_complete_cb_peer](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L7479) | The CB fork adds peer extraction/fallback and requires a complete CB peer when answering CQ. That helper is absent from the inspected upstream main windows. | An unresolved or partial peer cannot start a QSO. Admission traces from the real Qt harness remain R03c; no app AutoSeq exists. |
| AutoSeq progression: [auto_sequence](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L7511) | CB adds addressed non-standard messages and report/final recognition using local identity plus selected-partner context. Existing state, frequency tolerances and stop decisions still matter. | Compare accepted, ignored and stop decisions for specific states; preserve context for one-call reports. Predicate acceptance alone is insufficient. |
| Parsed message recovery: [processMessage](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L9004) | CB adds free-text/addressed-message fallbacks, selected-peer recovery and guarded report parsing. | Capture malformed, ambiguous, wrong-peer and one-call cases in R03c before porting parser/QSO behavior. |
| TX message generation: [genStdMsgs](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/widgets/mainwindow.cpp#L9778) | Two non-standard CB calls use a dedicated exchange branch; reports and finals can carry only the destination call. | Compare actual generated messages and encodings, including report sign/zero padding and length limits. A valid callsign does not guarantee that an arbitrary concatenated message fits FT8. |
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
roger-reports and finals. Treat this as a required scenario for R03b/R03c, not as
a generic template to apply to every message or mode. The current app only
validates station identity and displays remote decodes unchanged.

## Verification and limits

Run the commands in [the reference README](../reference/wsjtcb/README.md#reproduce).
R03a regenerates 315 results by compiling the original Fortran module and checks
the committed data in CI. The normal JVM suite compares 240 call results with the
production Kotlin validator. Four integration tests reject modified source,
stale expected results, changed input cases and a missing compiler.

The source audit checks all 16 manifest files at immutable revisions. It does not
compile Qt, packing modules or the complete decoder. The upstream
[test_cb_autoseq harness](https://github.com/vash909/WSJT-CB/blob/94f776d54946fe43db5e0bb9c966b1595c7b7d97/tests/ft8/test_cb_autoseq.cpp)
uses real admission/AutoSeq methods with substitute controls and `processMessage`;
even running it would not establish full TX-state-machine or radio behavior.

R03 remains open work: R03b must capture production payload bits, hash state and
round-trip results; R03c must capture parser/generation/AutoSeq decisions and
exchange traces, including cases beyond that partial desktop harness. Audio and
actual device interoperability follow the engine milestones. Keep R04's CB
compatibility gate pending until the required reference fixtures exist.

No application behavior changes in R03a. A phone smoke test may confirm that
saved settings, LAN and USB discovery remain available, but cannot validate these
reference algorithms or claim local FT8 support.

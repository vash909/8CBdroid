# Desktop parser and AutoSeq traces (R03c)

This host-only reference runs the pinned WSJT-CB parser, message generation and
receive-side QSO decisions. It adds no Android AutoSeq, DSP or transmission.
`qso-sources.json` pins eight unmodified files at
`94f776d54946fe43db5e0bb9c966b1595c7b7d97`, including the complete
`widgets/mainwindow.cpp`, `Radio.cpp`, `Decoder/decodedtext.cpp` and `stdmsg.f90`.
The existing packing manifest supplies their Fortran dependencies. All upstream
source bytes remain unchanged and are covered by the project GPL notices.

The generator extracts 16 complete methods, checking both the whole source and
each extracted fragment's SHA-256 before compilation. These include
`has_complete_cb_peer`, `auto_sequence`, `processMessage`, `genStdMsgs`,
`genCQMsg`, standard-call/mode helpers, `setTxMsg`, the DX-call change handler and
six TX radio-button handlers. The grid expression is also copied and checksummed.
The original `DecodedText` and Radio implementations are compiled directly.
`stdmsg_` and `save_dxbase_` use their real Fortran implementations.

## Run

Use Linux, Python 3, GNU Fortran 8+, a C++17 compiler, `pkg-config` and Qt 5 Core
and Network development libraries. For example, Debian/Ubuntu provide them in
`gfortran`, `g++`, `pkg-config` and `qtbase5-dev`. They are host test dependencies;
the Android build and APK do not depend on Qt or Fortran.

```bash
python3 scripts/wsjtcb_qso.py --check
python3 scripts/wsjtcb_qso.py --check --optimization O2
python3 -m unittest discover -s scripts/tests -p 'test_wsjtcb_*.py'
```

Run without `--check` to regenerate `qso.json` and review the diff. CI checks O0
and O2 against the same committed data. As with R03b, integer hash overflow wraps
and Fortran runtime checks are enabled. `FC`, `FFLAGS`, `CXX`, `CXXFLAGS`,
`PKG_CONFIG` and `LDFLAGS` support existing host toolchains. A locally extracted
Qt installation may need pkg-config search/sysroot settings, `-L`/`rpath-link`
flags and a runtime library path. On the current development PC, the ignored
`.tooling/qso-env.sh` selects the extracted compiler and Qt packages; source it
before these commands. This helper contains machine-specific paths and is not
needed with system-installed development packages.

## Corpus and format

`qso-cases.json` contains inputs only: 129 scenarios for FT8, normal operation,
fixed decoder columns and explicit station/control state. Scenarios include CB
numeric/slash calls, leading zeros, mixed amateur/CB controls, positive/negative
reports, zero padding, unknown/partial peers, wrong peers, malformed reports,
AutoSeq switches, frequency boundaries, duplicates and late/out-of-order events.

`qso.json` contains:

- Source/input/adapter checksums and the reference revision, without timestamps
  or machine paths.
- Each scenario's initial six generated messages and state snapshots after every
  operation: progress, selected TX slot, peer, report, offsets, CQ/final state and
  stop/log requests.
- Parser results from actual `DecodedText` calls: word captures, raw DE call and
  grid, standard flag, report fields, decoder columns and complete-peer admission.
- Production 77-bit payloads and cold `unpack77var` results for all 72 distinct
  generated messages, obtained separately through the R03b runner.

`parse` only inspects a decode; `auto` calls the real `auto_sequence`, which calls
the real `processMessage` when it decides to do so. `process` invokes the latter
directly, representing explicit message selection rather than automatic admission.
`context` is an explicitly simulated external event, such as selecting a peer or
marking the first 73 as sent. It is never reported as a decoder decision.

The two-station `relay` trace sends a station's actual generated text to the other
station's parser/AutoSeq. It covers CQ, reply, report, roger-report, RR73 and 73,
then confirms late finals are ignored after explicit completion context. The
initial CQ is selected manually through `processMessage`; subsequent messages
use AutoSeq. This is a text relay: it does not model RF, audio, transport errors,
hash-learning delays or a transmit-completion callback. Payload/unpack results
are checked independently, with cold hash results kept visible.

Each scenario has a fresh process, isolating native module state. Within a
scenario, state survives all steps. Two-station contexts have separate QSO/UI
state but share the process's desktop Fortran packing tables. They are not two
independent RF decoder sessions.

## Adapter boundaries

The runner uses a `QCoreApplication`, without starting its event loop. Lightweight
controls replace widgets. Text changes and exclusive TX selections synchronously
invoke the extracted production handlers. `msgtype` preserves the upstream
uppercase text assignment; its legacy JT65 colour classification/palette work is
omitted. Lookup represents an unavailable CALL3 database and records a visit.
`processed` counts visits to the end-of-reaction lookup point, not every entry
into `processMessage`; earlier state changes remain visible in snapshots.

Display/status effects and QSO timestamps are omitted. Timers/log/stop effects
are recorded rather than executed. CAT and TX activation hooks throw if reached.
The supported fixture state is FT8 with `SpecOp::NONE`, no transmission, contests,
VHF extras or next-call queue. The harness rejects unsupported input state keys.
It does not run the desktop event loop, full TX state machine, log persistence,
GUI interactions or actual PTT. Tests must not infer those capabilities.

## Desktop behaviors that need deliberate Android policy

- `DecodedText::isStandardMessage` calls `stdmsg_`, whose current implementation
  classifies from the packer's type outputs. It does not compare unpacked text.
  For example, a CQ with a CB call and locator can be marked standard even though
  the packed payload drops the locator. This flag is not a round-trip guarantee.
- Destination-only reports need selected-peer context: the message carries no
  sender identity. Changing that peer changes the report's inferred partner.
- Some unrelated free-text finals within tolerance advance the desktop QSO.
  With local `1AT106` and peer `26AT101`, `161XZ085 RR73` can select SIGNOFF and
  even replace the peer with `RR73`. These are recorded outcomes, not desired
  Android behavior or proof of a completed QSO.
- During CQ, echoed messages with local identity in the sender position can
  select our own call as the peer. Both hash orientations and the double-hash
  form are recorded; complete-call grammar alone does not exclude a self-QSO.
- Direct selection of `1AT106 R-XX` can select ROGERS without recording a valid
  report; AutoSeq rejects the same text. An older ordinary report can also move
  ROGERS back to ROGER_REPORT. The two-digit CB report path also accepts `+99`.
  Manual and automatic paths and numeric report bounds need separate checks.

R21/R22 must qualify peer identity, report/final admission and progression before
automating a QSO, and document deliberate differences from these desktop outcomes.
Successful reference regeneration is compatibility evidence, not permission to
implement every observed outcome as an Android rule.

## Phone verification

Install the PR's debug APK, save station settings, reopen the app, receive a LAN
decode and open Radio's USB discovery view. All text must remain English. There
are no new screens or local FT8 functions in R03c. The host reference job validates
the new behavior; a phone smoke covers only regressions in the existing client.

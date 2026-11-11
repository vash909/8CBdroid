# Local CB QSO advice

Operate connects qualified built-in-microphone decodes to the JVM QSO model and
shows a suggested next message. **Use suggested message** applies peer, report,
message, audio offset and opposite UTC period, and disables TX. Review the fields,
then explicitly Enable TX and Send one slot. Advice never arms, queues or repeats
a transmission. Manual/custom sending remains available; untracked output ends
an active tracked exchange instead of inventing sent facts.

## Start and follow an exchange

Select a complete CB sender with **Use peer**, or enter a peer manually. After a
completed CQ, a qualified reply addressed to your station can select the first
responder in the model; the TX fields remain unchanged until you apply its advice.
A CQ received while listening is observational and does not select a peer.

For the usual long-call exchange, a reply leads to Report, a report to R-report,
an R-report to RR73, and RR73 to 73. `RRR` leads to 73 after the report exchange,
as in the pinned desktop. The outgoing report uses the available received SNR
estimate until a report is actually sent; it is editable and not calibrated.
Once confirmed, sent/received reports remain fixed for that exchange. Changing a
manual report or sending custom/lossy/out-of-order text can leave the model's
rules: audio may still play manually, but that output cannot establish tracked
QSO progress.

Only completion of a full speaker slot confirms a tracked send. Prepared,
queued, cancelled, failed or partial output does not. Protocol completion
requires both reports and acknowledgements, actual local final output and peer
closing evidence: RR73/73, or the classic RRR acknowledgement. A final alone
cannot create a QSO. The state is an inference from local software/audio events,
not independent proof of radio TX, RF reception or a durable log.

**Reset QSO** disables TX and clears the exchange. Selecting another peer or
starting a new CQ discards prior context. Completed exchanges ignore late messages.
Stop, background/navigation, rotation, profile/source/station changes and receiver
failure invalidate active tracking. Restart RX and select/reset explicitly;
nothing resumes or transmits silently. Own-output pauses preserve the exchange
but discard its old receiver binding; recovery needs a new complete RX window.
All state, decisions and tickets are process-local.

## Admission boundaries

The core checks the payload and decoded text together, CRC/LDPC flags, resolved
unambiguous identities, finite signal metadata and complete timestamped observation windows (15 seconds normally, 13.5 seconds
for explicitly started AutoSeq). No unrecorded samples are padded.
It accepts only the active local receiver run, at/after its fresh-window boundary
and selection/last-output boundary, up to 15 seconds after the actual observation window ends. It
rejects stale/future/partial slots, duplicate payloads within a slot and older
slots after accepted progress. The app supplies a currently valid NTP/GPS mapping
whose revision matches the receiver. LAN, replay and imported recordings do not
feed advice. Receiver results are processed in slot order, nearest TX offset
first within a slot; only one message per slot can advance progress.

Directed named messages must match exact station/peer identities. A full own-call
source is an echo. Unknown and ambiguous hashes stay unresolved, including a
trailing local 12-bit hash that the pinned receive session cannot resolve; the
advisor does not fill it from displayed text or peer selection.

CB destination-only reports/finals carry no sender. They require the selected
peer, a confirmed directed local send, the current receiver context, and an offset
within 25 Hz of the peer RX or TX offset. That is contextual attribution, not an
on-air sender identity. Unrelated finals and free-text fragments never replace a
peer. The tighter frequency/causality checks deliberately constrain desktop
selected-peer recovery. A positively named selected peer replying elsewhere within
50 Hz of TX during the reply stage aborts tracking and disables TX, matching the
desktop stop decision while preserving the selected identity for inspection.

## Reference and device checks

`reference/wsjtcb/admission-cases.json` contains 25 decoder-line scenarios.
`wsjtcb_admission.py` runs the pinned original Qt/Fortran parser and AutoSeq methods,
independently packs each wire input, rejects lossy inputs, and records the desktop
next TX, peer and stop outcome in `admission.json`. Android expectations and safety
differences are separate input fields, never desktop-generated claims. Both O0/O2
must reproduce the same corpus. The test-only TSV carries exact wire payloads,
desktop observations and explicit Android policy; the main APK excludes it.

Shared JVM/Android checks cover the matrix and the six-message two-station relay
through actual payload unpacking/admission in both roles. Additional JVM/ViewModel
checks cover receiver/peer replacement, duplicate/old slots, metadata/text mismatch,
untracked output, explicit application and disarming. Offline scenarios synthesize
output-completion callbacks; they cannot qualify live audio integration.

```bash
source scripts/env.sh
timeout 180 python3 scripts/wsjtcb_admission.py --check
timeout 180 python3 scripts/wsjtcb_admission.py --check --optimization O2
timeout 600 bash scripts/check.sh
adb shell am instrument -w -r net.vash.ft8cbdroid.engineprobe/.OfflineEngineProbe
```

With the matching CI probe installed, expect `qso_admission_scenarios=25`,
`qso_admission_exchange_steps=6`, explicit PASS and instrumentation code -1.
The [manual acoustic checks](MANUAL_OPERATION.md#reference-and-physical-checks)
now also require actual PC-speaker/phone-microphone report/RRR/final reception and foreign-final rejection,
visible advice, explicit Use suggested message without arming, full speaker-slot
completion and QSO-model closure. Independently decode the three phone recordings.
Actual tested revisions, CI runs and device results are recorded in the feature
PR and [validation](VALIDATION.md). Explicit [AutoSeq](AUTOSEQ.md) consumes this advice through the foreground audio
owner; advice alone still cannot start it.

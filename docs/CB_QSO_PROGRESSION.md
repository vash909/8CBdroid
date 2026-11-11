# CB QSO progression model

`core/qso/CbQsoProgression` is an in-memory Kotlin/JVM domain model for one CB
exchange. It records selected peer, CQ/reply, reports, acknowledgements, signoff,
abort and protocol completion. The [local QSO advisor](QSO_ADVICE.md) now connects
qualified live decodes to this model and suggests messages on Operate. The domain
model itself neither chooses messages nor schedules audio. Applying advice and
each one-slot send remain explicit; there is no automatic execution or log.

## Events and ownership

Create a model with a complete saved `StationIdentity` and serialize its events
through one owner. Each update returns an immutable snapshot, disposition and an
English rejection reason when applicable. Observing a decoded message has no
progression effect. `SelectPeer` is explicit and accepts a complete CB callsign
different from the station's own call. Replacing a peer starts fresh and cancels
pending work. Binding the first peer retains an already sent CQ; selecting an
already selected active peer leaves the exchange intact. An explicit selection after completion/abort starts a new exchange.

`Received` takes already-qualified message semantics. A named sender and a
selected-peer context have distinct types: destination-only CB reports and
finals do not contain the sender on air. Context is supplied by the live
admission layer; the model neither parses a raw decode nor invents that identity.
CQ/reply require a named sender. Directed messages must address the saved station
and belong to its selected peer. Slot freshness, CRC/LDPC/hash qualification and
binding context to a receive session belong to `CbQsoAdvisor`. LAN/replayed decodes do not feed this
model.

`Prepare` freezes an explicit standard template, report, text and exact 77-bit
payload using the existing CB renderer and codec. Unsupported/lossy encodings
and custom text cannot establish progression. Only one ticket can be pending.
Preparing or queueing a message does not count as sending it. Return its exact
instance in `Sent` only after successful output completion; `Cancel` represents
cancellation or failed output without sent facts. Tickets cannot be reused after
completion, cancellation, peer replacement, reset or abort, or on another model.
The execution layer must also stop its own queued/playing audio when tickets are
invalidated; this pure model performs no I/O or cancellation side effect.

`Reset` clears all facts and pending work. `Abort` invalidates pending output and
ends an active exchange; it cannot turn an already completed exchange into an
abort. Recreation starts idle and never restores armed work. Snapshots/tickets
are process-local; there is no persistence or TX resumption contract.

## Progress and completion

Phases summarize confirmed facts, not the desktop's selected next TX field:
idle/calling, selected/replying, report exchange, acknowledgement, signoff and
complete, or aborted. Reports accept -50 through +99 dB. Once confirmed, a sent or
received report is fixed for that exchange. Duplicate reports are idempotent;
older messages cannot regress acknowledgement or replace a report. Early finals
cannot manufacture a report exchange. An R-report acknowledges a report actually
sent; `RRR` acknowledges but is not signoff.

Both reports, both acknowledgements, local final output completion and peer
closing evidence are required for completion. Peer RR73/73 provides final evidence;
the classic RRR path closes after our confirmed 73, while RRR itself is recorded
separately from a received final. For example:

| Calling station | Answering station |
| --- | --- |
| CQ sent, then qualify the responder | Explicitly select caller; reply sent |
| Report sent | Report received; R-report sent |
| R-report received; RR73 sent | RR73 received; 73 sent → complete |
| 73 received → complete | Late messages cannot reopen the exchange |

RR73 combines acknowledgement and final; 73 is final only. A received RR73 and a
prepared/cancelled 73 never count as a completed local signoff. Completion also
invalidates a pending retry, so a late output callback cannot advance it.
Protocol completion is a domain result, not independent proof of radio TX,
RF contact or a durable QSO record.

## Compatibility and checks

The six-message two-station relay comes from the pinned original desktop
[QSO corpus](../reference/wsjtcb/QSO.md). `qso_progression_reference.py` extracts
wire texts, parser fields and independent payloads from `qso.json`, records its
checksum, and checks the committed test-only TSV. Expected Android phases and
successful output callbacks are explicitly domain policy: the original harness
models desktop parsing/message selection, not real TX completion.

The shared JVM/Android probe checks the six-message relay in both roles, including
observation, preparation, sent callbacks and terminal handling. Five additional
shared safety scenarios cover cancelled reports, stale/cross-session tickets,
peer replacement/abort, wrong context/destination, early finals, duplicate reports
and pending retries at completion. JVM tests extend this to numeric/slash CB calls,
report boundaries, RRR, reset/recreation and identity normalization. Deliberately
unsafe desktop outcomes (self-selection, unrelated finals or report regression)
are not Android progression rules. See [validation](VALIDATION.md).

```bash
source scripts/env.sh
python3 scripts/qso_progression_reference.py --check
timeout 600 bash scripts/check.sh
```

The separate probe runs these domain scenarios on Android alongside its existing
offline engine checks. This establishes actual domain execution on that device;
it does not substitute for physical live integration, automatic acoustic exchange,
RF, CAT or USB tests. Test fixtures/adapters are excluded from the main app.

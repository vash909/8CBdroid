# Local QSO log

**Logbook**, available on Operate and in Settings, stores contacts locally on the
phone. No account or cloud service is required. Review pending exchanges, add a
manual contact, search callsigns/notes and edit saved entries. ADIF import/export
and desktop LAN log events are separate, unimplemented features.

## Completed local exchanges

The existing qualified local QSO model produces a review candidate only after
its report/acknowledgement/final conditions complete. Its inputs remain qualified
local receptions and confirmed complete speaker output. Received decodes, LAN
replays, selected peers, custom audio, aborts and the Sent list alone cannot
create a logged contact.

Candidates are saved before review and survive an app restart. Open **Review
and log**, check the details and explicitly confirm the **actual radio dial
frequency**. The initial number is the operating profile's reference, not CAT
readback. Saving atomically creates one contact and removes its candidate.
Repeated completion notifications or save retries cannot produce a second record
for the same completion. Cancel leaves the candidate pending.

The contact retains station/peer callsigns, UTC start/end, optional locators,
reports, confirmed dial frequency, FT8 mode and origin. Local audio also retains
the confirmed output's audio offset separately. Start/end bound the first and
last qualified FT8 slots (including an initial calling CQ), rather than callback
arrival times; they use corrected app UTC. Editing either UTC
value marks the saved time as operator entered. Completion describes protocol
and acoustic software evidence; operator review does not create an RF measurement.

## Manual entries and editing

**Add manual QSO** creates an explicitly tagged operator entry. Enter a complete
CB peer, UTC times ending in `Z`, the radio dial frequency in MHz and any known
reports/locators/notes. Confirm the dial before saving. Manual UTC values do not
change phone time or the NTP/GPS correction. A manual record's radio/audio route
is unverified. Cancel discards an unsaved manual draft.

**Edit QSO** updates the same record. Origin stays attached and concurrent/stale
edit versions are rejected instead of silently overwriting a newer edit. Dial
changes require confirmation again. Drafts survive Activity recreation; an
unsaved manual/editor draft is not promised across process death. Saved contacts
and pending candidates are durable.

**Search callsign or notes** is case-insensitive. It affects the log view only.
Multiple genuine contacts with the same station are allowed: duplicate protection
uses completion/record identity, not just the peer callsign.

## Worked filtering

Operate's **Worked** filter shows identified senders already present in the saved
log for the current station callsign. Pending candidates do not count. Unknown
senders cannot match; they still have no sender/country label. Changing a record
or station updates the display. Worked is unavailable until the log loads or
while a log error needs attention. It never changes reception, hash learning,
peer selection, QSO admission or AutoSeq input.

## Storage and recovery

The app-private SQLite database `qso-log.db` starts at schema version 1. Earlier
app installations had no log database to convert; station/profile/time stores
remain separate. Transactions protect candidate/record changes and edits use
explicit versions. There is no destructive migration or corruption fallback:
unsupported future schemas and damaged data cause a visible error, retaining the
existing database. Android's default corruption deletion is explicitly replaced.
Future schema migrations must be implemented and tested before increasing the
version. See the platform [SQLiteOpenHelper contract](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper).

If a completion write fails, the current process retains a retryable candidate
and shows that it still needs a successful write. Press **Retry log** before
closing the app; no software can claim durability for a failed write. An accepted
database transaction continues across Activity teardown. A save error keeps the
draft and stable record identity available for retry. Never clear app data to
recover a log error. Until export/backup support is implemented, app removal or
Android's clear-data operation can remove the local database.

## Reproduce checks

`scripts/check.sh` runs domain/completion, ViewModel, validation, retry and
filter tests with the existing compatibility corpus. The device checks additionally
exercise actual SQLite reopening, duplicate/concurrent saves, injected transaction
rollback/retry, stale edits, future-schema refusal and corrupt-byte preservation.
Temporary store fixtures use separate run-owned databases.

Install matching successful-CI app/device-check APKs. For the integrated test use
station `1AT106`, a Phone profile, working saved NTP and an unlocked phone beside
the PC's physical speaker/microphone. With synchronized Linux time, reference
compiler/Qt/Boost setup, NumPy/SciPy and an installed WSJT-CB `jt9`:

```bash
timeout 960 python3 scripts/qso_log_checks.py --output /private/path/log-check --jt9 /path/to/wsjtcb/bin/jt9
```

This runs one actual automatic acoustic answer, independently decodes its three
recorded phone outputs, reviews/logs it through the UI, injects a real failed
write and retries, exercises manual/edit/search/recreation, receives CQ messages
for Worked and then cold-restarts the process. Test-only contacts are removed
by their exact IDs and the initial logical log is verified unchanged. Keep
recordings, device information and reports private. These checks establish
software/acoustic persistence behavior, not CAT/USB, RF or a real radio contact.

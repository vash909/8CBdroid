# ADIF exchange and complete log backups

Open **Logbook** from Operate or Settings. File operations never start reception,
arm TX or change station/profile settings. Android's document picker selects the
source/destination; no broad storage permission or cloud account is required.

## Exchange saved contacts

- **Export ADIF** writes saved FT8 contacts as an ASCII `.adi` file.
- **Share ADIF** opens Android's share sheet with a read-only URI for the chosen
  recipient. The temporary cache holds at most four files; old files are removed
  on subsequent shares after 24 hours. For a permanent copy, use Export.
- **Import ADIF** reads an ADI file and opens a preview. No entries are saved yet.
  Check the callsigns, UTC times and exact dial frequencies, select the entries,
  confirm the review checkbox, then press **Import selected**.

The supported fields are CALL, STATION_CALLSIGN, MODE=FT8, QSO_DATE/TIME_ON,
QSO_DATE_OFF/TIME_OFF, FREQ, GRIDSQUARE/MY_GRIDSQUARE, RST_SENT/RST_RCVD and
COMMENT/NOTES. Fields are case-insensitive and their declared lengths delimit
values. Dates/times are UTC; four-digit times gain `00` seconds. If both end
fields are absent, start UTC is reused with a warning. A TIME_OFF without an end
date uses the start date, advancing one day if needed, also with a warning.
If STATION_CALLSIGN is absent, a valid current station callsign may be used and
is reported in the preview. FREQ is required; no frequency is guessed from BAND.

Only complete calls accepted by the app's pinned CB rules are imported. Unsupported
modes, invalid calls, dates, reports or fractional-Hz frequencies are rejected per
record. Additional fields are reported and discarded. Malformed lengths,
truncation, non-ASCII input or an unfinished record reject the whole file before
any write. ADX/XML and arbitrary log-format conversion are unsupported. Transfers
are limited to 4 MiB and 5,000 entries, with bounded fields per record.

An imported contact is labeled **Imported ADIF**, with **imported UTC** and an
unverified route. Imported metadata cannot assert a locally confirmed audio
output. Editing its UTC later records operator-entered UTC. Saved contacts count
for Worked only under the matching station, as with other saved log entries.

## CB band labels and notes

The [ADIF 3.1.8 specification](https://adif.org.uk/ADIF_Current) has no standard
11m band enumeration. **WSJT-CB: include 11m** writes BAND=11m only in the pinned
desktop's 26.965–27.405 MHz band. **Frequency only: omit 11m** omits BAND for
consumers that reject the extension. Both preserve the exact numeric FREQ in MHz,
including single-Hz digits; neither relabels CB as 10m. Other services may still
reject CB callsigns or frequencies, independently of the band-label choice.

ADI has one-second time precision. Unicode notes are preserved in the
`APP_8CBDROID_NOTES_UTF8_HEX` application field and represented as escaped ASCII
in COMMENT. Angle brackets are escaped too, because the pinned desktop loader
splits at record tags before decoding field lengths. A different logger may show
the escapes or discard app fields. Use a complete backup when exact original
notes, timestamps and provenance matter. This application field is local to
8CBdroid and is not a standard ADIF field.

## Duplicates and safe restoration

A possible ADIF duplicate has the same station, peer, start UTC second, exact
radio dial Hz and FT8 mode. Such rows are unchecked by default. Explicitly select
one only if it represents a separate contact. Existing entries are never
overwritten. If the log changes after preview, refresh and review it again.
Rejected records and warnings are indexed in the preview; up to 12 of each are
shown alongside the full counts.

**Back up log** writes a versioned `.8cblog` file containing saved contacts and
pending reviews, identities, edit versions, millisecond timestamps, Unicode notes,
original source/time basis and separate TX audio offsets. A SHA-256 checksum
detects accidental damage; it is not proof of authenticity or a radio contact.

**Restore backup** previews a non-destructive merge. Exact existing identities are
skipped. Conflicting identities are kept unchanged and reported; they cannot be
selected. Distinct historical identities are preserved even if their contact
fields look similar. Restoring a pending review never turns it into a saved QSO.
The selected merge is one atomic SQLite transaction. A failed transaction rolls
back; an already committed retry cannot duplicate its identities. Restoration
does not remove entries absent from the backup.

Cancel a picker/import to leave the log unchanged. Read/parsing work can be
cancelled; an accepted database transaction completes across Activity recreation.
A failed export offers **Retry export**, retaining the original frozen snapshot.
A failed provider may have left an incomplete output document: choose a new
destination or replace that document on retry. Android 8–10 require regular local
files for document I/O; download streaming-provider files locally first. Android
11+ also supports cancellable pipes, with a 30-second operation deadline.

Files contain station/peer calls, times, frequencies and notes. Choose sharing and
backup destinations accordingly. Keep backups outside app-private storage before
uninstalling/clearing app data. They contain the log only, not station settings,
calibration, profiles or time preferences. Desktop LAN QSO/LoggedADIF events remain
unimplemented.

## Reproduce the compatibility check

[PR #46](https://github.com/vash909/8CBdroid/pull/46) qualified file/UI/SQLite
behavior on A51 / Android 13. [Validation](VALIDATION.md#adif-and-log-restoration)
records the exact tested commit, CI and scope.

With Python 3, a C++17 compiler, pkg-config and Qt5Core development packages:

```bash
python3 scripts/wsjtcb_adif.py --check
python3 scripts/wsjtcb_adif.py --check --read /path/to/phone-export.adi
```

The harness checks source checksums, compiles the pinned desktop's QSOToADIF,
WorkedBefore loader and field extractor, and verifies the checked-in fixture.
The desktop fixture is imported/re-exported by JVM tests. DXCC lookup is stubbed;
contest activity, the desktop GUI, LAN events and radio hardware are outside this
harness. Source paths/revision and GPL provenance are in
[the ADIF source manifest](../reference/wsjtcb/adif-sources.json).

For example:

```bash
adb shell am instrument -w -e scenario log-files -e preview_run adif-bench-1 \
  net.vash.ft8cbdroid.test/net.vash.ft8cbdroid.validation.WaveformPhoneChecks
adb shell am force-stop net.vash.ft8cbdroid
adb shell am instrument -w -e scenario log-files-cold -e preview_run adif-bench-1 \
  net.vash.ft8cbdroid.test/net.vash.ft8cbdroid.validation.WaveformPhoneChecks
```

Device scenarios `log-files`, `log-files-cold` and `log-files-cleanup` exercise
real Android descriptors, UI review, cross-UID share permissions, SQLite upgrade,
rollback/retry, backup merge, recreation and cold-process persistence. They keep
fixture identities in a private checkpoint and remove only owned entries. Run
against a matching debug app/device-check APK, with an unlocked phone and a unique
alphanumeric run ID; keep the generated reports/log exports private.

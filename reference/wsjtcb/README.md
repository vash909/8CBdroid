# Pinned WSJT-CB predicate reference

This directory is test infrastructure for roadmap R03a. It does not implement
an Android FT8 engine. `source/cb_callsigns.f90` is copied byte-for-byte from
WSJT-CB commit `94f776d54946fe43db5e0bb9c966b1595c7b7d97`, path
`lib/ft8var/cb_callsigns.f90`, SHA-256
`2d632a85f0ab9fe7e636b371083d7c50f0866bb97aec7836974f467a207dc866`.
It remains under the upstream GPLv3 terms; this repository uses GPL-3.0-only.
See [the project license](../../LICENSE) and [notices](../../THIRD_PARTY_NOTICES.md).

`cases.json` contains inputs only: 240 complete-call cases and 75 type 4 predicate
cases. Boundary combinations exercise prefix/letter/suffix lengths, leading zeros,
slash forms, empty/incomplete calls and both hash positions. All inputs are ASCII;
Unicode and general whitespace normalization are outside this corpus. The small
Fortran harness calls the two real desktop predicates without replacing them.

The generated TSV is committed in `core/src/test/resources/wsjtcb/predicates.tsv`.
`C` means complete-call predicate, `T` means type 4 plausibility predicate. Fields
are kind, stable case ID, ASCII input encoded as hexadecimal (including the empty
string), and desktop result 0/1. Its header records the reference revision and
input-file SHA-256. The Kotlin parameterized test compares all 240 C results with
`CbCallsign.isValid`. The 75 T results are a reference corpus checked by the
Fortran job; there is no Android type 4 implementation to compare yet.

## Reproduce

Requirements: Python 3 and a host GNU Fortran compiler. Install `gfortran` through
your development system's package manager or provide an existing compiler using
`FC`. `FFLAGS` is optional. No Qt, Android NDK, GUI, audio device or radio is used.

```bash
python3 scripts/wsjtcb_reference.py --check
python3 -m unittest discover -s scripts/tests -p 'test_wsjtcb_reference.py'
source scripts/env.sh
bash scripts/check.sh
```

Run the first two commands from the repository root. To intentionally update
expected data, run `python3 scripts/wsjtcb_reference.py` without `--check` and
review the diff. Output is deterministic and has no timestamps or compiler-
version-dependent metadata. Generation compiles in a temporary directory and
checks the vendored source hash first. `FC` can be a compiler name or path; use
absolute paths in `FFLAGS`. A locally extracted compiler may need its own search
flags; ordinary `gfortran` needs none.

The regular Android/JVM check uses committed fixtures and needs no Fortran
compiler. A separate GitHub Actions job regenerates the entire corpus and checks
for changes; integration tests ensure modified source, stale results and changed
inputs fail. A source revision update must update the manifest, fixture and
compatibility inventory together and preserve the source notices.

## Audit the comparison sources

`sources.json` pins the inspected WSJT-CB and WSJT-X files and their SHA-256
values. Its `initial_snapshot` entry records WSJT-X 3.0.0-rc1 as the owner-identified
base, with exact commit mapping pending. The audited v3.0.0 comparison is a later
release and does not replace that initial snapshot. With explicit network access:

```bash
python3 scripts/wsjtcb_reference.py --check --audit-sources
```

This verifies the fixture, downloads all manifest files at immutable revisions,
checks their hashes and writes LF-normalized textual comparison diffs under
`.tooling/wsjt-reference-audit/diffs/`. Originals are retained in that ignored
directory. The audit does not fetch moving branches or silently skip mismatches.
It compares the Radio, false-decode filter and main-window files; it does not
perform a semantic analysis of the entire repositories. See
[the difference inventory](../../docs/WSJT_CB_COMPATIBILITY.md).

## Limits

These predicates describe text admissibility. They do not perform packing,
hash calculation/resolution, CRC/LDPC verification, RF decoding, or QSO progression.
An accepted `<...>` type 4 payload is not permission to start AutoSeq. The source
is test-only and neither it nor the fixture is packaged in the APK. Full bit,
hash, parser, AutoSeq and audio fixtures remain subsequent work in R03.

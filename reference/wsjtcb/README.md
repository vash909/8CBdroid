# Pinned WSJT-CB compatibility references

This directory is test infrastructure for roadmap R03a/R03b/R03c. It does not implement
an Android FT8 engine. `source/cb_callsigns.f90` is copied byte-for-byte from
WSJT-CB commit `94f776d54946fe43db5e0bb9c966b1595c7b7d97`, path
`lib/ft8var/cb_callsigns.f90`, SHA-256
`2d632a85f0ab9fe7e636b371083d7c50f0866bb97aec7836974f467a207dc866`.
It remains under the upstream GPLv3 terms; this repository uses GPL-3.0-only.
See [the project license](../../LICENSE) and [notices](../../THIRD_PARTY_NOTICES.md).

R03b also vendors the unmodified packing modules and their required dependencies.
[`packing-sources.json`](packing-sources.json) records their upstream paths and
checksums. The [packing/hash guide](PACKING.md) describes the 77-bit fixtures,
stateful scenarios, format and limits. Both references stay outside the APK.

R03c adds the [parser, generation and AutoSeq traces](QSO.md), using real desktop
methods beyond the upstream admission-only harness. Its source pins are in
`qso-sources.json`. All three references stay outside the APK.

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
`FC`. `FFLAGS` is optional. R03c additionally needs a C++17 compiler, pkg-config
and Qt 5 Core/Network development packages, as described in [its guide](QSO.md).
No Android NDK, GUI, audio device or radio is used.

```bash
python3 scripts/wsjtcb_reference.py --check
python3 scripts/wsjtcb_packing.py --check
python3 scripts/wsjtcb_packing.py --check --optimization O2
python3 scripts/wsjtcb_qso.py --check
python3 scripts/wsjtcb_qso.py --check --optimization O2
python3 -m unittest discover -s scripts/tests -p 'test_wsjtcb_*.py'
source scripts/env.sh
bash scripts/check.sh
```

Run the Python commands from the repository root. To intentionally update
expected data, run `python3 scripts/wsjtcb_reference.py` without `--check` and
review the diff; use `scripts/wsjtcb_packing.py` for the packing corpus and
`scripts/wsjtcb_qso.py` for the QSO traces.
Output is deterministic and has no timestamps or compiler-
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
values. Its `initial_snapshot` entry pins the official WSJT-X 3.0.0-rc1 source
archive by SHA-256 and records the checked initial CB import/V1.0 commits. The
audited v3.0.0 comparison is a later release and does not replace that archive. With explicit network access:

```bash
python3 scripts/wsjtcb_reference.py --check --audit-sources
```

This verifies the predicate fixture and downloads all 28 distinct source files
from the three manifests at immutable revisions,
checks their hashes and writes LF-normalized textual comparison diffs under
`.tooling/wsjt-reference-audit/diffs/`. Originals are retained in that ignored
directory. The audit does not fetch moving branches or silently skip mismatches.
It compares the Radio, false-decode filter and main-window files; it does not
perform a semantic analysis of the entire repositories. See
[the difference inventory](../../docs/WSJT_CB_COMPATIBILITY.md).

## Verify the original RC1 snapshot

The official release is a superbuild archive containing `src/wsjtx.tgz`. The
outer and inner SHA-256 values and early CB comparison results are recorded in
[`initial-snapshot.json`](initial-snapshot.json). This provides an exact source
artifact pin even though the CB import does not preserve an upstream Git parent.
The released V1.0 tag is `WSJT-CB`, not `WSJT-CB-1.0.0`.

With a local WSJT-CB Git repository containing the first import and V1.0 commits:

```bash
curl -fL --connect-timeout 15 --max-time 600 \
  -o .tooling/wsjtx-3.0.0-rc1.tgz \
  https://downloads.sourceforge.net/project/wsjt/wsjtx-3.0.0-rc1/wsjtx-3.0.0-rc1.tgz
python3 scripts/wsjtcb_snapshot.py \
  --archive .tooling/wsjtx-3.0.0-rc1.tgz \
  --repository /path/to/WSJT-CB
```

The checker reads the local Git repository without changing its checkout. It
verifies archive checksums before unpacking in memory, compares regular files and
fails if the recorded results differ. UTF-8 text without NUL bytes is also
compared with CRLF normalized to LF; binary changes remain visible. There is no
filesystem extraction and no attempt to infer a Git commit from matching subsets.
The archive download and full comparison are explicit local audit steps, not
additional network dependencies of the regular build. CI tests the comparison
semantics and corrupted-archive rejection with tiny synthetic archives.

## Limits

The R03a predicates describe text admissibility. R03b separately checks real
packing, unpacking and hash calculation/learning. R03c checks parser, generation
and receive-side QSO progression within its documented adapter boundaries.
These references do not perform CRC/LDPC verification or RF decoding.
An accepted `<...>` type 4 payload is not permission to start AutoSeq. The source
is test-only and neither it nor the fixtures are packaged in the APK. The full
desktop TX lifecycle and audio/device interoperability follow the engine and
operating milestones.

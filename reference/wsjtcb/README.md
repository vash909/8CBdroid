# Pinned WSJT-CB compatibility references

This directory contains pinned desktop reference sources and generated test
corpora for the Android implementation. Original Fortran/C++ files are host-only;
the main app uses its separate Kotlin CB codec, derived runtime country table and
source-built native channel backend. The separate `engineprobe` APK includes
test fixtures; the main APK excludes those fixtures. See [architecture](../../docs/ARCHITECTURE.md) and
[current validation](../../docs/VALIDATION.md).

WSJT-CB reference: **`94f776d54946fe43db5e0bb9c966b1595c7b7d97`**. Original
vendored source bytes, whitespace and line endings must remain unchanged;
`.gitattributes` disables text conversion under `source/`. Sources retain their
upstream GPL terms; the repository uses GPL-3.0-only. See [LICENSE](../../LICENSE)
and [notices](../../THIRD_PARTY_NOTICES.md).

| Reference | Pins / outputs | Purpose |
| --- | --- | --- |
| CB predicates and comparison sources | `sources.json`, `cases.json`, `core/src/test/resources/wsjtcb/predicates.tsv` | Complete-call/type 4 predicates and immutable WSJT-X comparison revisions. The TSV is relative to the repository root. |
| [Payloads and hashes](PACKING.md) | `packing-sources.json`, `packing-cases.json`, `packing.json` | Original packing/unpacking and stateful RX behavior. |
| [QSO traces](QSO.md) | `qso-sources.json`, `qso-cases.json`, `qso.json` | Extracted parser/generation/AutoSeq methods, not a complete desktop operating session. |
| [CB countries](../../docs/CB_OPERATING_CONTEXT.md) | `context-sources.json`, `core/src/main/resources/wsjtcb/countries.tsv`, `core/src/test/resources/wsjtcb/country-cases.tsv` | 350 runtime CB divisions and 1,249 original desktop lookup cases; paths relative to repository root. Only the runtime country table is packaged in the main app. |
| [Channel fixtures](../../docs/FT8_ENGINE.md#independent-desktop-checks) | `channel-sources.json`, `channel/channel.json`, four WAVs | 131 payload/codeword/tone vectors and original desktop waveforms. |
| [Generated PCM qualification](../../docs/OFFLINE_WAVEFORM.md) | `pcm-sources.json`, `harness/pcm_decode.f90` | Original desktop LDPC/CRC/unpacker with supplied time/frequency and a Goertzel adapter. |
| [Recorded-slot qualification](../../docs/RECORDED_SLOTS.md) | `recordings/recordings.json`, eight derived/control WAVs plus four channel WAVs | Fixed noisy/shifted/corrupt/negative cases; known-reference checks, not full decoder parity. |
| [Decoder profile corpus](../../docs/DECODER_PROFILES.md) | `recordings/benchmark.json` | 40 slots/profile with reproducible PCM hashes; generated on demand by Python/Kotlin, without adding main-app recordings. |
| JVM/phone adapters | `core/src/test/resources/wsjtcb/messages.tsv`, `hash-sessions.tsv` | Test-only exported data from the original desktop results; no runtime fixture lookup. Paths are relative to the repository root. |

`source/cb_callsigns.f90` is copied from `lib/ft8var/cb_callsigns.f90`, SHA-256
`2d632a85f0ab9fe7e636b371083d7c50f0866bb97aec7836974f467a207dc866`.
The other manifests record their own paths and checksums. The full desktop
engine is not an Android dependency.

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
Fortran job. The runtime Kotlin type 4 codec is checked separately against the
packing/message corpora; this predicate TSV alone does not establish its behavior.

## Reproduce

`acoustic-cases.json` supplies complete physical phone/desktop exchange inputs.
`scripts/wsjtcb_acoustic.py --check` regenerates their payloads and cold/warm
receive text with the original pinned packer/unpacker. The expected data is
packaged only in the Android test APK, at `app/src/androidTest/assets/acoustic.json`.
See [acoustic interoperability](../../docs/ACOUSTIC_INTEROP.md) for actual audio
setup and independent desktop search of physical phone-speaker recordings.

Use the complete sequential command list and host prerequisites in
[development](../../docs/DEVELOPMENT.md#desktop-reference-and-native-checks).
Text predicates/packing need GNU Fortran; QSO traces additionally need C++17,
pkg-config and Qt 5 Core/Network. Channel/PCM checks also need Boost headers and
a CMake/Ninja host build. Host reference sources are not compiled with the
Android NDK and do not access a GUI, audio device or radio.

On this Linux PC, run reference compilation/generation through the controlled
wrapper, for example:

```bash
source scripts/env.sh
timeout 180 python3 scripts/wsjtcb_reference.py --check
timeout 180 python3 scripts/wsjtcb_packing.py --check
timeout 180 python3 scripts/wsjtcb_qso.py --check
```

Run the Python commands from the repository root. To intentionally update
expected data, omit `--check` from the controlled reference command and review
the diff; use `scripts/wsjtcb_packing.py` for the packing corpus and
`scripts/wsjtcb_qso.py` for the QSO traces.
Output is deterministic and has no timestamps or compiler-
version-dependent metadata. Generation compiles in a temporary directory and
checks the vendored source hash first. `FC` can be a compiler name or path; use
absolute paths in `FFLAGS`. A locally extracted compiler may need its own search
flags; ordinary `gfortran` needs none.

The regular Android/JVM check uses committed fixtures and needs no Fortran
compiler. A separate GitHub Actions reference job reproduces committed corpora
and checks for changes; integration tests ensure modified source, stale results
and changed inputs fail. A source revision update must update the manifest, fixture and
compatibility inventory together and preserve the source notices.

## Audit the comparison sources

`sources.json` pins the inspected WSJT-CB and WSJT-X files and their SHA-256
values. Its `initial_snapshot` entry pins the official WSJT-X 3.0.0-rc1 source
archive by SHA-256 and records the checked initial CB import/V1.0 commits. The
audited v3.0.0 comparison is a later release and does not replace that archive. With explicit network access:

```bash
timeout 300 python3 scripts/wsjtcb_reference.py --check --audit-sources
```

This verifies the predicate fixture and downloads all 37 distinct source files
from the four manifests at immutable revisions,
checks their hashes and writes LF-normalized textual comparison diffs under
`.tooling/wsjt-reference-audit/diffs/`. Originals are retained in that ignored
directory. The audit covers `sources.json`, `packing-sources.json`, `qso-sources.json`
and `channel-sources.json`. It does not fetch moving branches or silently skip
mismatches. The PCM verifier separately hash-checks its vendored files against
`pcm-sources.json`; the optional online audit does not fetch those extra sources.
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
timeout 180 python3 scripts/wsjtcb_snapshot.py \
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

The R03a predicates describe text admissibility. R03b checks real packing,
unpacking and hash behavior; R03c checks parser/generation and receive-side QSO
decisions within [its adapter boundaries](QSO.md#adapter-boundaries). These text
references do not perform channel CRC/LDPC verification. Later channel/PCM/
recording checks cover specific integrity and waveform cases, but use a
supplied-sync adapter for independent desktop decoding. They do not port or run
the entire desktop candidate search and plausibility filters.

An accepted `<...>` rendering is not an identified peer or permission for
AutoSeq. Small fixed noise/corruption cases do not establish sensitivity or a
false-positive rate. The full TX lifecycle, sustained operation and radio/audio
interoperability remain separate implementation and verification work. Refer to
[validation](../../docs/VALIDATION.md) for actual Android outcomes.

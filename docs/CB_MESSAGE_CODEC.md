# Offline CB message codec

The **Settings → Message tools** page previews FT8 text packing without audio or radio access.
Automatic mode selects free text (0.0), standard messages (1), portable standard
messages (2), or a full callsign plus a 12-bit hash (4). Explicit **Free text**
mode always selects 0.0. The preview shows the encoded text and warns whenever
content changes. No preview result arms TX or authorizes a QSO peer.

## Reference and implementation

`core.message.CbMessageCodec` is pure Kotlin/JVM. Selected algorithms are ported
from the original pinned WSJT-CB files, under this project's GPL-3.0-only terms:

- `reference/wsjtcb/source/packjt77.f90`: `pack77`, `pack77_1`, `pack77_4`,
  `pack28`, `split77`, `packtext77`, `unpacktext77`, `ihashcall`.
- `reference/wsjtcb/source/packjt77var.f90`: cold `unpack77var` type 1/2/4 paths,
  including the CB complete-call exception and trailing-hash admission.
- `reference/wsjtcb/source/chkcall.f90`: standard/compound call recognition.

Reference commit: `94f776d54946fe43db5e0bb9c966b1595c7b7d97`. Original vendored
bytes remain unchanged. There is no runtime fixture lookup, Fortran dependency
or reuse of ft8_lib's incompatible amateur text codec.

Input is at most 37 printable ASCII characters. ASCII lowercase becomes
uppercase and repeated spaces collapse, independently of phone locale. Unsupported
characters, unresolved `<...>` TX input, telemetry, macros and contest exchanges
are rejected explicitly. Supported free text uses the desktop's 42-character
alphabet and carries the first 13 characters. Portable standard calls use `/P`
(type 2) or `/R` (type 1); CQ modifiers and grid replies use the desktop fields.
The codec does not implement other FT8 subtypes or every desktop decoder filter.

`PackResult.Encoded` contains the 77-bit payload, canonical input, typed format,
cold decoded message, transmitted-text preview and `contentChanged`. A desktop
payload rejected by the supported unpacker returns `Unsupported`, including the
three invalid CB CQ fixtures. The stateless TX preview can render hashes using
only identities explicitly supplied in that same input. This does not teach RX.

`UnpackResult.Decoded` exposes text parts and typed `UnresolvedCall` references
with the original hash width/value. Unknown hashes render as `<...>` and never
become invented calls. Static TX hash calculation is included; learned-call
storage, collision resolution, local-station context and warm RX behavior are
provided separately by [R06 hash sessions](CB_HASH_SESSIONS.md). The stateless
codec remains cold and cannot learn from a TX preview. Message parsing does not
verify channel CRC/LDPC. Recorded reception supplies integrity-checked DSP frames
before using this codec; the Messages simulator accepts explicit payloads for
offline inspection. A decoded string is not AutoSeq permission.

## Losses and desktop edge cases

- `CQ 1AT106 JN45` encodes as `CQ 1AT106`; type 4 has no locator field.
- `<1AT106> 26AT101 R-10` encodes as `<1AT106> 26AT101`; the report is lost.
- Two long CB calls without hash brackets can select free text and truncate:
  `1AT106 26AT101 -10` becomes `1AT106 26AT10`.
- Free text longer than 13 characters is truncated and flagged.
- Standard locators longer than four characters lose their subsquare.
- The supported structured-report input range is -50 through +99. Desktop
  encoding aliases +51 through +99 to negative reports on unpacking; e.g.
  `K1ABC W9XYZ +99` becomes `K1ABC W9XYZ -02`. The preview flags this change.
  Out-of-range reports are rejected. These aliases are compatibility evidence,
  not a recommendation to transmit an out-of-range measured report.

The existing undefined-hash TX case remains excluded: `<...> 26AT101` is an
explicit raw RX fixture, not a deterministic encoder input. See
[the original reference correction](../reference/wsjtcb/PACKING.md#undefined-tx-placeholder-discovered-during-r04).

## Validation

The corpus now has **82 desktop packing cases**, 15 hash vectors and **96 RX
scenarios**, plus 72 R03c generated messages. `scripts/wsjtcb_codec.py` exports
**154** independently generated message cases into a test-only TSV, recording
source JSON checksums. JVM tests compare exact payloads and cold decoded text,
or require explicit rejection when the desktop rejects the payload. Additional
tests cover four explicit raw RX cases, visible losses, hash collisions, statelessness, invalid inputs,
explicit free text, reserved types and 2,000 deterministic arbitrary payloads.
The raw channel corpus is extended to **131** vectors; the four desktop WAVs stay
unchanged. Recorded-slot checks now add a small noisy corpus; full desktop
decoder acceptance, sensitivity and live interoperability remain unqualified.
See [recorded-slot limits](RECORDED_SLOTS.md) and [validation](VALIDATION.md).

```bash
source scripts/env.sh
timeout 180 python3 scripts/wsjtcb_packing.py --check
timeout 180 python3 scripts/wsjtcb_packing.py --check --optimization O2
timeout 60 python3 scripts/wsjtcb_codec.py --check
timeout 600 bash scripts/check.sh
```

Host compiler setup and verified-CI phone deployment are described in
[development](DEVELOPMENT.md). The local probe helper installs a local APK;
use CI artifacts when recording PR device evidence.

The separate probe APK also checks all 154 text cases on Android before its
existing four-WAV DSP smoke. CI regenerates desktop results and verifies the
TSV adapter, then builds both APKs. Preserve station data during APK updates;
see the [deployment guide](DEVELOPMENT.md#deploy-and-test-on-a-phone) for signing
conflicts and test-device setup.

## Phone verification

Install the verified CI APK and unlock the test phone:

1. Open **Settings → Message tools**; `CQ 1AT106` should show the same encoded text.
2. Enter `<1AT106> 26AT101 R-10`; verify the lost-report warning and preview.
3. Enter `HELLO WORLD 123456789`; verify preview `HELLO WORLD 1` and a warning.
4. Enter `<...> 26AT101`; verify explicit rejection.
5. Select **Free text**, enter `ABC123`; verify free-text output. Automatic mode
   should report unsupported telemetry instead.
6. Rotate the phone; input and selected mode should survive. Reopening after
   process termination is not promised. Check Station/LAN/Radio still work and
   all text remains English on a non-English phone.

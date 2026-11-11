# Live local reception

Operate can decode complete microphone UTC slots automatically. Local
results are labeled **Local microphone** and kept apart from LAN messages and
recorded-file results. This is foreground reception; [AutoSeq](AUTOSEQ.md) has a separate explicit
execution owner. Radio PTT and
radio QSOs remain later work. The separate [speaker preview](AUDIO_PREVIEW.md) and [UTC scheduling](UTC_AUDIO_SCHEDULING.md) stop reception before playing. Current device evidence is in [validation](VALIDATION.md).

## Use

1. In Station, save your station identity when known and apply a valid NTP/GPS
   measurement. An unknown station is allowed for reception, but cannot resolve
   hashes referring to your identity. No manual or system-clock fallback exists.
2. In Audio, select the input and enable **Decode live local FT8**. This enables
   UTC assembly too. Select **Local Standard** (default) or **Local Fast**.
3. Press **Start microphone** and grant permission. Confirm the actual route.
   Initial partial audio is discarded; a full UTC period must finish before
   its results appear. This normally takes 15–30 seconds plus decode time.
4. Scroll to **Live local decodes** for UTC, text/hash status, offset, DT,
   experimental SNR and the time source/estimated uncertainty. GPS uncertainty
   remains explicitly unavailable when the receiver does not report it.
5. Stop to clear local results and learned calls. The latest complete PCM slot
   can still be exported through the existing UTC WAV action after cleanup.
   Every restart is explicit and creates a cold local callsign session.

The live mode/profile are temporary ViewModel choices, separate from recorded
file profiles. Stop, rotation, backgrounding, leaving the receive screens for Settings or Chat, station changes,
input changes and capture failures cannot publish late live results or restart
the microphone. A running decode must clean up before Start is available again.
Changing/disabling UTC assembly also disables live decoding. Time-setting/apply
actions stop capture before changing the mapping.

## Work and interpretation

The recorder submits immutable complete slots without waiting for the decoder
or Compose. `LiveSlotReceiver` owns one serial worker and **one latest pending
slot**. If decoding falls behind, a newer pending slot replaces the old one;
the visible skipped count increases. No partial audio is padded or stitched.
One working assembly and one latest export snapshot remain in the capture owner.

The shared native gate still bounds work across live reception, file decoding
and waveform generation. Fast/Standard retain their R09 integrity checks,
candidate/iteration caps and deadlines. A busy/deadline failure is visible and
counts as a failed slot; reception can decode the next complete slot. There is
no retry loop on the same PCM. [Profile limits](DECODER_PROFILES.md) apply.

At most **60 decoded rows** are retained, newest slots first. Empty periods
advance the slot counter without erasing earlier messages. Each run owns a
bounded `CbMessageSession` initialized from the saved station snapshot. Full
identities from supported integrity-checked messages are staged during a batch
and committed afterward, so a later slot can resolve them. A frame cannot
resolve itself or another frame in the same batch using new staged identities.
Unknown, ambiguous, capacity-limited and unsupported messages remain explicit.
Files, LAN, message previews and later runs cannot warm this cache.

DT/SNR retain the recorded decoder's limitations: onset minus 0.5 seconds,
80 ms search grid and an experimental 2500 Hz SNR estimate. The clock mapping
and audio timestamps retain [R11 timing limits](UTC_RECEIVE_SLOTS.md). Integrity
checks alone do not authenticate a peer or complete a QSO.

## Verify

JVM tests exercise slow DSP/latest-slot replacement, bounded history, CB learning
boundaries, session isolation, profile snapshots, empty slots, busy/deadline
recovery and cancellation with deliberately late results. Capture ViewModel
tests feed timestamped complete slots and verify Stop/restart/station changes.

For a physical test, deploy matching successful CI app/test APKs, unlock the
phone and grant microphone permission. Put it near the PC speakers. The Linux
PC needs `pw-play`, a running PipeWire audio session and a synchronized system
clock; the phone uses its saved NTP server. The helper verifies the pinned
desktop fixture hashes, then schedules CQ and directed CB WAVs at UTC boundaries.
It does not supply PCM to the phone, synthesize decoder results or send LAN decodes.

```bash
source scripts/env.sh
timeout 210 bash scripts/check_live_phone.sh
```

The phone must decode `CQ 1AT106`, resolve the later `<1AT106> 26AT101`, Stop
and clear results/cache, then render `<...> 26AT101` after an explicit cold
restart. The probe uses an unknown local station for this cache test without
changing saved station settings. Three 15-second fixtures play through the PC
speakers at half stream volume; global PC/phone volumes are unchanged. Metadata
reports stay under ignored `.tooling/`; microphone PCM is not exported by this
helper. Temporary charging stay-awake settings are restored and only this app
is stopped on exit.

Also inspect the actual live controls/results, permission recovery, Stop during
work, rotation, background/reopen and navigation. Verify local source labels and
that neither LAN nor recorded-file results become local rows. Record actual CI
commit/device/results before merge; pending hardware checks are not passes.


## Coordinated speaker bench

Message tools can explicitly start the same built-in microphone receiver and
pause it for silent calibration or speaker output. Optional recovery after
successful output starts a fresh local/hash/slot session; partial and self-TX
slots are not retained. Stop, errors and leaving the page never recover it.
See [phone audio](PHONE_AUDIO.md); this does not add background reception or
radio VOX qualification.

# UTC receive slots (R11)

The app can synchronize its receive clock through **NTP or GPS/GNSS**, timestamp
microphone PCM and retain complete 15-second UTC windows. Android's system clock
is never changed. There is no manual offset or unsynchronized fallback.
[Live reception](LIVE_RECEIVE.md) optionally decodes these slots. Playback,
TX scheduling and radio operation remain later milestones.

## Synchronize time

In **Station → Time synchronization**, choose a regional preset or **Custom NTP
server**, then **Save time settings** when the selection changes. Custom input
accepts an IPv4 address, IPv6 address or hostname, without a URL or port; UDP/123
is used. Brackets around IPv6 are accepted; interface suffixes are not supported.
No location permission or location lookup is used to choose an NTP region.

Press **Measure NTP**, or **Measure GPS** with precise location permission,
Location enabled and a clear sky view. Review the source, correction, age and
estimated uncertainty, then press **Apply measured time**. Applying or saving
time settings stops/clears microphone capture; restart explicitly. A measurement
does not start capture or adjust Android time.

Only server preferences persist. Measurements stay in memory, expire after ten
minutes and are discarded on process restart. A 50 ppm holdover estimate increases
the displayed uncertainty with age; this is an assumption, not an oscillator
calibration. Expiry, a changed correction or a detected Android clock step stops
UTC assembly. Synchronize again; the app never falls back to the system clock.
The system date must already be within one day for NTP era/offset validation.

## Regional NTP presets

These are operator-published services, not availability or nearest-server
guarantees. Choose a nearby region and compare the measured round trip and
uncertainty. The worldwide anycast option is useful outside a regional service's
access area. The list was checked on 2026-11-06.

| Region | Preset | Operator reference / access boundary |
| --- | --- | --- |
| Worldwide | `time.cloudflare.com` | [Cloudflare global anycast service](https://developers.cloudflare.com/time-services/ntp/usage/). |
| Europe | `ntp.metas.ch` | [METAS, Switzerland](https://www.metas.admin.ch/en/time-dissemination-via-ntp); public service alias. |
| North America | `time.nist.gov` | [NIST, United States](https://tf.nist.gov/tf-cgi/servers.cgi); recommended service hostname. |
| South America | `a.ntp.br` | [NIC.br public stratum 2 service](https://www.ntp.br/conteudo/estrutura/), Brazil. |
| Asia | `ntp.nict.jp` | [NICT, Japan](https://www.nict.go.jp/en/sts/ntp_faq.html); this client enforces 256 seconds between NICT requests. |
| Africa | `time.nmisa.org` | [NMISA's NTP registry entry](https://support.ntp.org/Servers/PublicTimeServer001771); intended service area South Africa. |
| Oceania | `pool.msltime.measurement.govt.nz` | [MSL, New Zealand](https://www.measurement.govt.nz/about-us/official-new-zealand-time/about-time); connections outside NZ are blocked. |

NTP is an explicit one-shot request with no background polling or automatic
server failover. All requests are separated by at least 64 seconds in the client;
NICT additionally has its own 256-second limit. DNS has a three-second caller
deadline and one bounded worker; if the OS resolver remains blocked, further DNS
work is rejected rather than accumulating threads. UDP receive has a separate
three-second deadline and cancellation checks every 200 ms. Failure leaves the
previous applied correction unchanged until it expires.

The [RFC 5905](https://www.rfc-editor.org/rfc/rfc5905) parser validates the exact
48-byte reply, server/version/leap/stratum, connected peer, echoed random origin,
nonzero receive/transmit timestamps and ordering. It unfolds the 2036 era near
the device date, rejects local clock steps, and includes round-trip/root delay,
dispersion, precision and local quantization in the uncertainty estimate. Initial
uncertainty above 100 ms is rejected. This is one-server SNTP, without NTS,
authentication, multi-source selection or a guarantee against network asymmetry.

## Offline GPS/GNSS time

The GPS backend uses three consistent, fresh, non-mock `GPS_PROVIDER` fixes
and their boot-clock timestamps. Android guarantees that this provider's UTC
originates in the satellite constellation's clock; see
[Location.getTime](https://developer.android.com/reference/android/location/Location#getTime()).
Network/fused providers, last-known locations, stale/future fixes and simulated
locations cannot synchronize the app. Raw GNSS measurement support is not
required, so this works with the app's Android 8+ baseline.

The fix API does not report a UTC accuracy bound. GPS measurements and slots
therefore display **timing uncertainty not reported**, rather than an invented
millisecond estimate. Position accuracy is never treated as time accuracy.
Three advancing fixes must agree with elapsed realtime within 10 ms; this checks
consistency and does not prove absolute UTC accuracy. Compare against NTP/desktop
and qualify the actual receiver before drawing timing-performance conclusions.

Coordinates are ignored and never stored or included in reports. The client
needs no Internet connection; cold-start acquisition time and device assistance
depend on the phone/sky view. Acquisition times out after 90 seconds. Cancel,
provider disable, backgrounding or rotation removes the location listener before
acquisition ends. Approximate permission is insufficient; a late permission grant
cannot restart a cancelled measurement. No background location permission or
service is used.

## Capture and export

1. In **Audio**, enable **Assemble UTC slots**, select an input and start the
   microphone after applying valid NTP/GPS time.
2. The app waits for complete windows on UTC seconds 00, 15, 30 and 45. Starting
   partway through a period discards that partial; it does not insert silence.
3. Inspect completed count, progress, discarded partials, measured audio drift,
   latest UTC start and estimated timing uncertainty. Stop capture, then use
   **Save latest UTC slot WAV**. The filename carries UTC; the WAV itself has no
   embedded UTC metadata. Import later with that UTC in the recorded-slot decoder.

Capture requests `AudioRecord` timestamps in `TIMEBASE_BOOTTIME`, matching
`elapsedRealtimeNanos`. Startup audio is excluded until at least three distinct timestamps remain
consistent for 500 ms, after the initial 500 ms warmup. Startup clock shifts reset
only this candidate timeline; if it cannot settle within three seconds, capture
stops. Once confirmed, timestamp faults stop capture rather than resetting its
mapping. DSP sample times account for cumulative sample
positions and subtract the resampler's 64-input-frame delay at both input rates.
Receipt/poll time is not used as the sample's capture time.

Only contiguous samples build a slot. Index gaps/duplicates discard partials;
stale/reset timestamps, inferred recorder overrun, clock steps above 50 ms or
departure from the nominal audio timeline above 10 ms stop capture. This fixed
budget accommodates recorder timestamp variation observed on the A51 (including
a 3.06 ms departure); it does not retime samples or silently move the anchor. Smaller drift
is observed/reported, not rate-corrected. Grid quantization is below one 12 kHz
sample; measured-source uncertainty, holdover growth and 10.1 ms are reported for
NTP slots; GPS uncertainty remains explicitly unavailable. Android's
pipeline timestamps do not prove that every device-side sample loss is observable.

A complete slot is exactly 180,000 samples of mono 12 kHz PCM. Export is PCM16
RIFF/WAVE, 360,044 bytes, with filtered overshoot saturated to valid PCM bounds.
UI observations are conflated and never suspend the audio reader on the main
thread. A complete slot remains available after Stop even if the UI has not yet
rendered its completion. Timing-guard failures log only numeric frame/clock
metadata for diagnosis, without audio or station/location data.

Slot-only mode retains one working slot and the latest complete slot. Optional
live decoding adds one active slot and one latest pending slot; neither mode has
an unbounded queue. Stop discards the unfinished window and retains the last
complete one for export. Input/mode changes, ordinary rotation/background or
leaving Audio clear captured slots. A pending document export preserves the
stopped snapshot through the picker, including cancellation/retry. Nothing rearms.

## Verification

JVM tests cover NTP packet faults/era arithmetic and actual UDP loopback,
GPS freshness/mock/provider/consistency checks, expiry/no fallback, clock steps, both
capture rates/filter delays, missing samples/partial windows and persistence/
ViewModel cancellation. These do not establish hardware interoperability.

After verified matching CI app/device-check APK deployment, unlock the phone.
Allow microphone permission and keep media volume audible. The NTP/audio probe
uses the saved server, captures two real UTC windows and plays only a two-second
1000 Hz reference tone. GPS requires precise location permission and a real
satellite timing solution; it does not accept an injected fix as proof.

```bash
source scripts/env.sh
ADB_LIBUSB=1 bash scripts/controlled.sh 130 bash scripts/check_utc_phone.sh ntp-audio
ADB_LIBUSB=1 bash scripts/controlled.sh 130 bash scripts/check_utc_phone.sh gps
```

For capture-timing investigations, `bash scripts/capture_timestamps_phone.sh`
collects ten seconds of boot/monotonic timestamp and frame-counter metadata using
the matching CI development probe. It grants that probe microphone permission,
saves no audio and plays no tone. Collection alone is not a UTC-slot qualification
pass; use the actual slot/export checks above.

The UTC helpers do not install APKs or grant permissions. They temporarily keep
the screen awake while charging, including USB-C connections reported as AC,
then restore the original stay-awake setting. They stop only the app on exit, require explicit PASS and
verify the private JSON report against instrumentation output. Audio remains in
private phone cache unless explicitly copied/exported. Also exercise the actual
UI: presets/custom host validation, save/reopen, permission denial/allow,
measurement cancel/retry/apply, slot start/stop/export/picker retry, and
rotation/background without restart. Record tested CI commit/device/results in
the PR and [validation](VALIDATION.md); failed or blocked hardware checks stay open.

Android contracts: [AudioRecord timestamps](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,%20int)),
[AudioTimestamp boot clock](https://developer.android.com/reference/android/media/AudioTimestamp),
and [GPS fix UTC and boot timestamps](https://developer.android.com/reference/android/location/Location#getTime()).

# Operating profiles

Open **Settings → Operating profiles** to save up to 32 named setups. The active
profile's configuration is restored at app startup, with reception stopped and
TX off. A profile is configuration, not a connection or hardware qualification.

## Create and apply

1. Choose **New profile**, give it a unique name and select a connection setup:
   phone audio / VOX, USB CAT, or WSJT-CB LAN.
2. Set the reference dial in integer Hz. It labels local operation; it never
   tunes a radio. LAN continues to display the desktop's reported frequency.
3. For USB CAT, load/search the compiled Hamlib serial-model catalog, choose a
   real model and set baud, data bits, stop bits, parity and flow control. Dummy
   and non-serial models cannot be chosen. The baud must fit the model's declared
   range. These settings are saved only: no CAT transport/control exists yet.
4. Choose input and output route types independently of CAT. Input preference
   selects an available Android input of that type. If absent, reception is
   unavailable until the route appears or you explicitly select another input.
   Output is saved for future playback; nothing plays or keys VOX.
5. Choose Fast/Standard decoding and the LAN listen port/optional desktop IPv4
   filter. A blank filter accepts a desktop from any address. Profiles use the
   existing IPv4 LAN contract; NTP's separate custom field accepts IP/hostname.
6. Press **Save profile**, then **Apply profile**. Saving alone does not replace
   the running configuration. Applying waits for microphone/listener cleanup,
   clears their session results and any applied NTP/GPS correction, and changes
   configuration without starting a receiver. Synchronize time again for local
   FT8, then start RX explicitly. Plain Monitor still needs no time measurement.

The active marker identifies the setup restored on next app startup. Saving
edits to that setup affects the next startup even if you have not applied those
edits yet. Operate shows the name/reference of the configuration actually applied
in this process. Temporary source/input/decoder/LAN edits in other settings pages
are not written back to a profile; reapply the saved setup to restore them.

Save or **Discard profile changes** before selecting another profile. Drafts
survive rotation/navigation through the retained ViewModel, but are not durable
until saved. Delete asks for confirmation; apply another profile before deleting
the active one. Profile operations never arm TX or call radio open/PTT functions.

## Persistence, migration and failures

The app uses an atomic DataStore file with a bounded, versioned format. A previous
installation with no profiles starts with **Default**; existing callsign, locator
and NTP server files stay untouched and remain shared across profiles. Default
uses phone audio, Standard decoding, 27.265 MHz and UDP port 2237.

Saved fields are profile ID/name, setup mode, reference frequency, optional Hamlib
model ID/version/label, serial options, separate input/output route types,
decoder choice and LAN settings. Android device IDs/paths, USB grants/handles,
clock measurements, running sessions, messages, PCM and armed/TX state are never
serialized. Actual attached devices and permissions are checked again at use.

Profile read errors, unknown versions and corrupt data do not silently replace
the file with defaults. Reception stays unavailable until configuration can be
restored or explicitly reapplied. Retry loading after a read failure. Failed
saves retain the draft for retry; failed application leaves reception stopped.
After a Hamlib version change or missing model, explicitly load and reselect the
radio before saving/applying; model IDs are not silently remapped.

## Checks

JVM tests cover validation/catalog mismatches, actual DataStore close/reopen,
legacy-install defaults, corrupt/future-file preservation, failed save/retry,
profile selection/deletion, cleanup ordering and route-type selection with
changed device IDs. On a phone also verify:

- Create, save and apply phone, USB-preference and LAN profiles; inspect the
  actual configuration and idle TX state after each operation and cold restart.
- Invalid name/port/rate/address, duplicate names, unsaved-edit discard, delete
  confirmation/cancel and model search/selection.
- A missing USB audio input disables capture without selecting the phone mic.
- Actual microphone Monitor stops before profile application; actual UDP status
  arrives only after explicit Start RX with the saved endpoint.
- Rotation/navigation retain drafts, background/process recreation do not start
  RX, and applying a profile invalidates an existing time correction.

USB serial, USB audio and physical radio behavior require separate hardware
checks. Saving a model or route does not qualify it. See [validation](VALIDATION.md),
[Hamlib](HAMLIB.md) and [the interface guide](INTERFACE.md).

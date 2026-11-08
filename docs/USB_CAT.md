# USB CAT integration

## Discovery and permission

The **Settings → USB / CAT** page can enumerate attached USB devices, show VID/PID and device
ID, identify serial drivers/port counts and request Android access to a recognized
serial interface. Attach/detach updates the list while the app process is alive.
Tap **Find USB devices** for an initial or manual refresh. Permission requests
are explicit, one at a time, and can be retried after denial.

Discovery opens no USB device or serial port,
changes no baud rate or control line, and sends no CAT/PTT command. Access granted
means Android permission only; it does not mean that a radio is connected. Unknown
USB devices remain visible but cannot be selected for a serial permission request.
A USB audio interface may legitimately have no recognized serial driver.

## Chosen approach and dependency

Use Android `UsbManager` plus Java USB serial drivers, following the approach in
[FT8CN CableSerialPort](https://github.com/N0BOY/FT8CN/blob/a98fbfaec379a0c7a07d2a50c27be223aef948d8/ft8cn/app/src/main/java/com/bg7yoz/ft8cn/connector/CableSerialPort.java).
Its [prober](https://github.com/N0BOY/FT8CN/blob/a98fbfaec379a0c7a07d2a50c27be223aef948d8/ft8cn/app/src/main/java/com/bg7yoz/ft8cn/serialport/UsbSerialProber.java)
identifies `usb-serial-for-android` as its source. This project uses the upstream
library rather than copying FT8CN's connector or its vendored driver snapshot.

Dependency: `com.github.mik3y:usb-serial-for-android:3.11.0`, MIT license, from
[JitPack](https://github.com/mik3y/usb-serial-for-android/tree/3.11.0). The Gradle
repository filter allows only this module from JitPack. The upstream license is
included in APK assets and linked in [third-party notices](../THIRD_PARTY_NOTICES.md).
Descriptor discovery and permission handling use no native driver. A separate
source-built Hamlib library now exposes a model catalog and software-only Dummy
check; it does not consume these USB grants or open ports. See [the Hamlib guide](HAMLIB.md).
Hamlib is the selected protocol direction; the Android USB serial bridge and
actual radio operations still require implementation and physical tests.
[Operating profiles](OPERATING_PROFILES.md) can save a Hamlib model/version and
serial preferences, but do not bind an adapter or open a port.
The FT8 DSP library remains separate from CAT.

Use only the default prober's recognized devices. Do not force an unknown device
into a serial driver. Distinguish identical adapters by Android device path and
ID, not vendor ID alone. Permission callbacks must match the pending request URI,
path and device ID, and the current Android grant is rechecked. Removed devices
invalidate pending requests. Receivers are owned by the ViewModel's application-
context inventory and are unregistered when the owner is cleared; no Activity is
retained. UI rotation preserves the inventory through the ViewModel. No operation
starts automatically when permission is granted.

See [Android USB host documentation](https://developer.android.com/develop/connectivity/usb/host)
for enumeration, permission and device lifetime.

## Phone verification

Without OTG hardware:

1. Install the debug APK, open **Settings → USB / CAT** and tap **Find USB devices**.
2. An OTG-capable phone with nothing attached should report **No USB devices
   found**; a device without host support should explain that host mode is unavailable.
3. Rotate, switch tabs and reopen the app. There should be no automatic permission
   request, crash or claim that a radio is connected. Verify English text with a
   non-English phone locale.

When a data/OTG adapter and serial interface are available:

1. Attach the interface, refresh and record phone/Android version, interface
   chipset, VID/PID, driver and port count. Verify it against the actual hardware.
2. Tap **Request USB permission** for one interface. Deny the Android prompt:
   the app should show denial and allow retry. Retry and grant: it should show
   access granted while still reporting that radio connection is unimplemented.
3. Detach while a permission prompt is pending; dismiss it and confirm the device
   disappears. Reattach and ensure the old callback cannot grant access to a new
   request. Rotate during a prompt and verify the result reaches the retained owner.
4. If available, attach two adapters with the same VID/PID and verify permission
   state belongs to the selected device only. Test a non-serial USB device as well.
5. No serial output, RTS/DTR changes or CAT/PTT commands should occur. Discovery never
   opens a serial port.

Automated JVM tests cover permission-state isolation, retry, duplicate-request
blocking, removal, replacement, revocation and ViewModel cleanup. They do not test
the Android system dialog or physical USB interoperability. No phone/interface
combination is certified by the discovery feature; physical OTG/CAT tests remain
pending. Current accepted checks are recorded in [validation](VALIDATION.md).

## CAT support boundary

No radio/OTG-cable combination is qualified. CAT support requires a separate
backend and physical validation with a documented phone/radio/cable setup:

- Choose the pinned Hamlib model ID and actual radio serial settings, interface
  and port. Define frequency/status fixtures and keep upstream driver declarations
  distinct from verified Android operations.
- Implement open/read/close behind the radio contracts, with bounded I/O, detach
  recovery and all advertised write/PTT capabilities disabled for the read-only PR.
- Verify permission, connection, identity/frequency read, repeated reconnect and
  unplug with the documented phone/radio/cable. Keep CAT and USB audio separate.

A recognized FTDI/CP210x/CH34x/CDC or other serial interface is transport support,
not evidence that every radio behind it speaks the same CAT protocol.

# Commercial distribution

8CBdroid currently uses **GPL-3.0-only**. This permits selling app copies,
paid downloads and support. Repository privacy does not change the license.

When distributing an app derived from GPL code, preserve copyright and
license notices and provide the complete Corresponding Source as required
by GPLv3 section 6. For binary downloads, provide equivalent source access
at no additional charge. An upstream link alone does not supply the source
of your modified version. Recipients retain their GPL modification and
redistribution rights.

Private development does not require a public GitHub repository. Distribution
to customers triggers the applicable recipient-facing obligations. Making
a repository private does not revoke rights on copies already received.

Proprietary or dual licensing requires the necessary copyright permissions.
It cannot override third-party GPL rights in WSJT-X, WSJT-CB or other code.
This document grants no proprietary exception to those components.

The app already includes Kotlin ports of selected WSJT-CB message/hash routines
and a source-built ft8_lib/KISS FFT channel backend. Original desktop Fortran,
Qt and Boost are host-test dependencies; the complete WSJT-CB desktop engine
and Hamlib are not bundled. Keep [third-party notices](../THIRD_PARTY_NOTICES.md)
aligned with the actual APK and audit additions before integration. An
independently implemented UDP client requires a different assessment from a
port that incorporates GPL source; using the same protocol alone does not settle the application's license.

This is technical project guidance. Have the concrete release and its
dependency inventory reviewed before commercial distribution.

References: [GPLv3 sections 4–6 and 10](https://www.gnu.org/licenses/gpl-3.0.html),
[official commercial-use FAQ](https://www.gnu.org/licenses/gpl-faq.en.html#GPLCommercially),
[private development and source availability](https://www.gnu.org/licenses/gpl-faq.en.html#GPLRequireSourcePostedPublic).

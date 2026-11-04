# Working on 8CBdroid

- This repository is an Android development skeleton; do not imply radio,
  audio, DSP or LAN functionality exists until its backend is implemented.
- Keep hardware and networking outside Compose and behind the core contracts.
- Use Kotlin/JVM modules for protocol logic that can be tested without Android.
- Preserve WSJT-CB compatibility against the pinned reference in
  `docs/WSJT_CB_INTEGRATION.md`. Add meaningful wire/DSP fixtures when expanding it.
- Run `bash scripts/check.sh` after code changes. Environment: `source scripts/env.sh`.
- Never commit `.tooling/`, `local.properties`, signing keys or credentials.
- Update the English README and relevant docs when behavior or setup changes.

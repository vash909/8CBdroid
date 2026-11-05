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
- Use English for all project content, including documentation, UI text, code
  comments, commit messages and bodies, PR titles and descriptions, and review
  comments. Apply this rule to all new or modified content.
- All software in this repository must use English. The entire application's
  user-facing language must be English, including
  screens, navigation, labels, buttons, placeholders, validation and error
  messages, dialogs, notifications, and accessibility descriptions. Treat
  existing non-English UI text as pending translation, not as a convention
  to preserve.
- Keep the repository public, as requested by the owner on 2026-11-05, unless
  the user explicitly requests another visibility change.
- Preserve GPL-3.0-only and `docs/COMMERCIAL_DISTRIBUTION.md`: selling is allowed,
  but attribution alone does not satisfy GPL source and recipient-rights obligations.

## Development and pull requests

- Develop in small feature branches and open focused PRs in this repository.
  Each PR should deliver one independently verifiable behavior. Reserve direct
  commits to `main` for trivial documentation fixes explicitly requested by the user.
- Keep PR titles and descriptions short, clear and immediately understandable.
  State the resulting behavior and the relevant validation; avoid implementation diaries.
- Before requesting review, run local tests, lint and the debug build, then check
  the GitHub Actions result. Include concrete steps for testing on a phone when relevant.
- After creating the PR and its build, wait for the user's explicit OK before
  merging. Never merge or enable auto-merge without that approval. Passing checks
  alone are not permission to merge.
- Use JVM tests for domain/protocol rules and persistence/ViewModel tests for
  saved settings. Use a real phone for USB OTG, audio, VOX and radio interoperability.
- Keep the initial development sequence focused: persistent station settings,
  LAN heartbeat/status, LAN decodes, then a first documented radio/cable backend.

## Debug phone deployment

- The agent owns deployment via ADB to the connected, authorized debug phone
  (currently Samsung Galaxy A51 / SM-A515F). After successful PR CI, download
  that run's debug APK artifact, verify it matches the current PR commit,
  install/update it, launch the app and report the result. Do not ask the owner
  to install APKs manually when ADB is available.
- Use bounded ADB commands and preserve existing app data. Never uninstall the
  app or clear its data to resolve a signing conflict without explicit approval.
  If the phone is disconnected or unauthorized, report deployment as pending.
- Deployment does not replace phone testing or the owner's explicit merge approval.

## Local resource limits

- On this Linux PC, run builds, reference generation and native checks through
  `bash scripts/controlled.sh SECONDS COMMAND ...`: one job, 2 CPU cores,
  4 GiB RAM, no swap and a finite deadline. Use `--no-daemon` for Gradle.
- Do not launch a second heavy job while one is running. Check progress at least
  once per minute; stop and investigate an expired or stalled job before retrying.
- Never terminate unrelated editor processes or clear user settings to recover a build.

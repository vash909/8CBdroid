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
- Keep the repository private unless the user explicitly requests a visibility change.
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

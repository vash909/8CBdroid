# Documentation index

8CBdroid currently supports LAN monitoring and offline CB message/audio-file
experiments, plus foreground microphone capture and local FT8 reception. Start with the [repository README](../README.md) for screen-level
capabilities and a first test. The [validation record](VALIDATION.md) separates
implemented behavior, device evidence and checks still pending.

## Use and test the app

| Guide | Purpose |
| --- | --- |
| [Operating profiles](OPERATING_PROFILES.md) | Save radio/audio/LAN setups, restore disconnected configuration and handle validation/migration errors. |
| [Interface and navigation](INTERFACE.md) | Operate, settings, source selection, manual acoustic TX controls and the Chat placeholder. |
| [CB countries and receive filters](CB_OPERATING_CONTEXT.md) | Pinned offline divisions, sender context, All/CQ/To-me display filters and unknown identities. |
| [Local QSO log](LOGBOOK.md) | Durable completion reviews, operator-confirmed contacts, manual entry, edit/search, recovery and Worked filtering. |
| [Build and deployment](DEVELOPMENT.md) | Set up tools, build/check, obtain CI APKs and test through ADB without losing settings. |
| [WSJT-CB LAN integration](WSJT_CB_INTEGRATION.md) | Configure the desktop endpoint, monitor decodes, request Replay and understand the UDP/lifecycle contract. |
| [Offline CB message codec](CB_MESSAGE_CODEC.md) | Supported encodings, lossy previews and rejected input. |
| [CB hash sessions](CB_HASH_SESSIONS.md) | Explicit receive simulation, staged learning, reset and collision ambiguity. |
| [Foreground acoustic AutoSeq](AUTOSEQ.md) | Explicit automatic CQ/answer, bounded retries, immediate RX recovery and periodic NTP refresh. |
| [Manual acoustic operation](MANUAL_OPERATION.md) | Explicit CB peer/messages, one-slot speaker sends, receive recovery and limits. |
| [Acoustic desktop interoperability](ACOUSTIC_INTEROP.md) | Reproducible numeric/slash exchanges, hash resets, degraded input and independent desktop decodes of physical phone audio. |
| [One-shot UTC scheduling](UTC_AUDIO_SCHEDULING.md) | Measured output latency, even/odd slots and cancellation of stale work. |
| [Coordinated phone audio](PHONE_AUDIO.md) | Exclusive microphone/speaker ownership, opt-in receive recovery and physical acoustic checks. |
| [Speaker audio preview](AUDIO_PREVIEW.md) | Explicit generated-slot playback, level/ramps, Stop, focus and output lifetime. |
| [Offline WAV generation](OFFLINE_WAVEFORM.md) | Generate/export a fixed FT8 slot with explicit frequency, level and loss consent. |
| [Microphone capture](MICROPHONE_CAPTURE.md) | Select an input, inspect levels/processing, capture/resample and save recent audio. |
| [UTC receive slots and time synchronization](UTC_RECEIVE_SLOTS.md) | Regional/custom NTP, offline GPS/GNSS time and complete microphone windows. |
| [Spectrum and waterfall](SPECTRUM.md) | Local microphone visualization, bounded history, zoom/pan and display-only RX/TX cursors. |
| [Live local reception](LIVE_RECEIVE.md) | Automatically decode complete microphone slots, distinguish local/LAN results and reset bounded receive sessions. |
| [Recorded slots](RECORDED_SLOTS.md) | Import WAVs, supply UTC and interpret DT, estimated SNR and message/hash flags. |
| [Hamlib catalog and Dummy API](HAMLIB.md) | Source pin, Android native build, searchable model metadata and software-only checks; no hardware CAT. |
| [USB discovery and CAT scope](USB_CAT.md) | Enumerate adapters and request access; distinguish this from a real CAT/audio connection. |

## Develop and verify

| Guide | Purpose |
| --- | --- |
| [Local QSO advice](QSO_ADVICE.md) | Qualified live peer messages, next-message advice, explicit application and desktop differences. |
| [CB QSO progression model](CB_QSO_PROGRESSION.md) | Domain events, selected-peer context and protocol completion behind the advice layer. |
| [Architecture](ARCHITECTURE.md) | Module boundaries, state ownership, persistence and Android I/O. |
| [Development environment](DEVELOPMENT.md) | Tool versions, build/check commands, CI artifacts and device testing. |
| [Decoder profiles and work bounds](DECODER_PROFILES.md) | Fast/Standard budgets, native cancellation, admission limits and finite device measurements. |
| [FT8 channel engine](FT8_ENGINE.md) | Native dependency pin, ABI/contracts, search limits and reference checks. |
| [Validation evidence](VALIDATION.md) | Tested revisions, corpus sizes, A51 observations and remaining verification gaps. |
| [WSJT-CB compatibility inventory](WSJT_CB_COMPATIBILITY.md) | Reference provenance, CB changes versus WSJT-X and deliberate Android differences. |
| [Reference infrastructure](../reference/wsjtcb/README.md) | Fixture manifests, reproduction commands and source audits. |
| [Payload/hash fixtures](../reference/wsjtcb/PACKING.md) | Original desktop packing/unpacking behavior and known edge cases. |
| [QSO decision traces](../reference/wsjtcb/QSO.md) | Host-only parser/generation/AutoSeq adapters; no Android automation. |

## Distribution and upkeep

- [Project license](../LICENSE), [third-party notices](../THIRD_PARTY_NOTICES.md)
  and [commercial distribution](COMMERCIAL_DISTRIBUTION.md) cover the current
  dependency/source obligations. There is no release-signing or store-publishing
  pipeline yet.
- When behavior changes, update the README capability table and the relevant
  feature guide. Record device/CI evidence in [validation](VALIDATION.md) with
  the tested commit and the limits of the check.
- Put reusable setup commands in [development](DEVELOPMENT.md) and feature
  contracts in their dedicated guides. Preserve historical reference findings,
  but label their scope so they do not describe the whole current app.
- Never turn a planned test into a recorded pass, or a small offline corpus into
  a claim of radio interoperability. Keep private backups, device identifiers
  and personal recordings out of committed documentation.

- [Continuous reception](CONTINUOUS_RECEPTION.md): service ownership, notification Stop, permissions and phone checks.

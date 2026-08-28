# Echo360

Echo360 is a modern companion, launcher and management platform for Xbox 360 RGH.

The project is evolving from the original Echo360 Companion prototype into a native Android application, while preserving the working Xbox-side tooling and protocol experiments.

## Vision

Echo360 aims to make an Xbox 360 RGH feel like a living modern platform: a game-first launcher, library, diagnostics, safe transfers, remote control, integrity checks and, later, a resident Xbox-side `EchoCore.xex`.

## Core pillars

- **EchoHome / EchoLibrary** — modern game-first launcher experience
- **EchoTransfer** — safe, differential transfers with Fast/Background routes
- **EchoDoctor** — diagnostics for games, TUs, plugins and configuration
- **EchoRemote** — control and telemetry from Android
- **EchoCore** — future resident Xbox 360 plugin and first-party API

## Status

Early development. The Android migration starts with a native Kotlin + Jetpack Compose foundation. The existing Companion remains a reference implementation until native modules reach feature parity.

## Safety principles

- Never commit Xbox credentials or console-unique secrets.
- Prefer read-only diagnostics before mutation.
- Back up critical configuration before automated changes.
- No destructive remote file operations without explicit user intent.

## Repository layout

```text
app/          Android application
core/         shared domain logic (introduced as the project grows)
protocols/    Aurora / FTPdll / future EchoCore protocol implementations
xbox/         Xbox-side tools, scripts and future EchoCore
legacy/       preserved Companion implementation during migration
docs/         architecture and roadmap
```

## License

No license has been selected yet.

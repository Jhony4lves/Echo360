# Echo360

Echo360 is a modern native Android companion, launcher and management platform for Xbox 360 RGH.

The original Echo360 Companion prototype has now been superseded by the native Kotlin + Jetpack Compose application for the v1 software scope. Aurora NOVA, Aurora FTP and FTPdll remain compatibility providers while the project advances toward a resident Xbox-side `EchoCore.xex`.

## Vision

Echo360 aims to make an Xbox 360 RGH feel like a living modern platform: a game-first launcher, library, diagnostics, safe transfers, local save protection, remote controls, integrity checks and, through EchoCore, a first-party Xbox-side service.

## Core pillars

- **EchoHome / EchoLibrary** — game-first launcher, library, artwork, metadata and observed sessions
- **EchoTransfer** — differential transfers with Fast/Background/Auto routing, retries, verification and history
- **EchoDoctor / EchoIntegrity** — evidence-first diagnostics for games, runtime state, DashLaunch, storage and Vault hashes
- **EchoSync / Save Vault** — bounded read-only Xbox → Android snapshots with SHA-256 manifests
- **EchoStats** — retained observed play-session analytics
- **EchoRemote** — documented NOVA controls plus restricted Aurora FTP restart/reboot/shutdown actions
- **EchoTU / EchoMods safety** — read-only TU inventory and a verified rollback gate for future mutation
- **EchoCore** — resident Xbox 360 service and future first-party EchoLink API

## Status

### Android v1: 100% software-complete

Phases 0–5 of the native Android v1 scope are implemented and CI-covered. Open work in those phases is intentionally limited to target-console/device validation or post-v1 mutation/remediation that requires a proven rollback/restore path.

Current compatibility stack:

- EchoLink v1 Android client + portable C reference server
- Aurora NOVA
- Aurora passive FTP
- FTPdll active FTP

Next system layer:

- physical EchoCore XEX / EchoLink proof on the target Xbox
- promotion of native read-only EchoCore capabilities after ABI/hardware validation
- pairing/authentication before privileged EchoCore commands
- HUD/Boost/Pad/event-driven features after the resident core is proven stable

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the phase split and hardware gates.

## Safety principles

- Never commit Xbox credentials or console-unique secrets.
- Prefer read-only diagnostics before mutation.
- Never convert unavailable evidence into a corruption or success claim.
- Back up the exact target before any future automated mutation.
- Future mod/TU writes must have a verified, complete rollback snapshot that covers the target.
- No arbitrary remote file or FTP command surface from player-facing UI.
- Hardware validation is required before Xbox-side EchoCore behavior is called proven.

## Repository layout

```text
app/          Native Android application
core/         Shared/domain logic as the project evolves
protocols/    Compatibility and EchoLink protocol material
xbox/         Xbox-side tools, experiments and EchoCore work
legacy/       Preserved Companion reference implementation
docs/         Architecture, contracts and roadmap
```

## License

No license has been selected yet.

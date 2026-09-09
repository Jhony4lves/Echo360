# Echo360

Echo360 is a modern native Android companion, launcher and management platform for Jhony's Xbox 360 RGH.

The active architecture is intentionally external to the Xbox runtime: the Android app talks to Aurora NOVA, Aurora FTP and FTPdll over the local network. The earlier resident EchoCore / EchoLink experiment was retired after real-console testing showed that a resident XEX introduced black-screen and boot/runtime fragility that the project does not need.

## Vision

Echo360 aims to make an Xbox 360 RGH feel like a living modern platform without making console boot depend on custom resident code: game-first library, diagnostics, safe transfers, repair automation, local save protection, remote controls and integrity checks from external devices.

## Core pillars

- **EchoHome / EchoLibrary** — game-first launcher, library, artwork, metadata and observed sessions
- **EchoTransfer** — differential transfers with Aurora/FTPDll Auto routing, retries, verification and history
- **EchoDoctor / EchoIntegrity** — evidence-first diagnostics for games, runtime state, DashLaunch, storage and Vault hashes
- **EchoFix** — evidence-first repair recipes for misplaced Xbox content, including installer payloads exposed directly or trapped inside GOD containers
- **EchoSync / Save Vault** — bounded read-only Xbox → Android snapshots with SHA-256 manifests
- **EchoStats** — retained observed play-session analytics
- **EchoRemote** — documented NOVA controls plus restricted remote console actions
- **EchoTU / EchoMods safety** — read-only TU inventory and a verified rollback gate for future mutation

## Active connectivity stack

- Aurora NOVA
- Aurora passive FTP (`:21`)
- FTPdll active FTP (`:7564`)
- Auto routing based on measured transfer throughput with bounded failover

No resident EchoCore service is required for the current product architecture.

## EchoCore / EchoLink status

The resident EchoCore direction is **retired from active builds and CI**. Historical source, protocol notes and experiments may remain in the repository only as technical reference. They are not part of the Android APK, are not required on the Xbox and must not be treated as the current connectivity plan.

Any future Xbox-side helper must be optional, manually/on-demand invoked first, reversible, and must prove stability on the real console before it can influence the active architecture.

## Safety principles

- Never commit Xbox credentials or console-unique secrets.
- Prefer read-only diagnostics before mutation.
- Never convert unavailable evidence into a corruption or success claim.
- Back up the exact target before risky mutation.
- Never blindly overwrite a different-size game/content target.
- No arbitrary remote file or FTP command surface from player-facing UI.
- Hardware validation is required before console-specific behavior is called proven.
- Resident/boot-time Xbox code is not a dependency of Echo360.

## Repository notes

`app/` contains the active Android application. Historical Xbox-side experiments and documentation can remain for reference, but active build/release decisions follow the architecture above.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## License

No project-wide license has been selected yet. Third-party components/references are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

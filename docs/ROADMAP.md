# Echo360 Roadmap

## Status legend

- ✅ **Software complete** — implemented in the native Android app and covered by CI; target-console validation may still be pending.
- 🧪 **Hardware pending** — the software path exists, but the behavior still needs proof on the target Xbox 360 / Galaxy S24 setup.
- 🚧 **Active** — implementation belongs to the next project layer and is still evolving.

## Phase 0 — Native foundation ✅

- Android app shell in Kotlin + Jetpack Compose
- Game-first EchoHome navigation
- CI build validation
- Architecture and secret-handling rules

## Phase 1 — Native connectivity ✅

- Xbox endpoint configuration
- Secure local credential storage
- Console discovery / reachability status
- Aurora NOVA client
- Aurora FTP client
- FTPdll active-mode client
- Shared path normalization (`Hdd1:` ↔ FTP namespace)

## Phase 2 — EchoTransfer parity ✅ software / 🧪 hardware

- Graphical local/Xbox folder picker
- Smart compare by relative path + size
- Missing/different plan
- Fast / Background / Auto
- Session reuse and graceful QUIT
- Bounded retry and failover
- Progress, speed and ETA
- Post-upload remote SIZE verification
- Persistent transfer history

Remaining work is target-hardware validation of Aurora passive, FTPdll active, retries, cancellation, failover, SIZE and real throughput behavior.

## Phase 3 — EchoHome + EchoLibrary ✅ software / 🧪 hardware

- Installed-game discovery from Aurora `content.db`
- Real Aurora cover/background cache with RXEA decoding
- Continue Playing
- Recent / favorites / backlog
- Kinect / local multiplayer / genre / status metadata and filters
- Per-game launch screen
- Play-session timeline and observed playtime
- Launch readiness and launch-context evidence
- Per-game EchoIntegrity summary

Remaining work is target-console/device validation of the Aurora database/assets, NOVA launch/runtime behavior and physical UX.

## Phase 4 — EchoDoctor ✅ software / 🧪 hardware

- Game integrity findings
- TU / Media ID runtime diagnostics
- DashLaunch plugin/config diagnostics
- RAM / temperature / storage telemetry
- Unified full scan
- Crash context and safe-launch recommendations
- Provider-neutral boundaries prepared for EchoCore read-only sources

Remaining work is target-console validation and promotion of EchoCore candidate read-only contracts after physical ABI proof.

## Phase 5 — Deeper integration ✅ software v1 / 🧪 hardware

- EchoStats / sessions
- EchoSync Save Vault: bounded read-only Xbox → Android snapshots
- EchoIntegrity local Vault SHA-256 verification
- EchoRemote: documented NOVA pause/resume/screenshot plus restricted Aurora FTP restart/reboot/shutdown
- EchoTU: evidence-first read-only TU inventory and runtime observation
- EchoMods / mutable TU safety gate: verified Save Vault + hash integrity + target containment required before any future write executor can be approved

The v1 intentionally does **not** expose automatic mod/TU mutation or restore. Those operations remain blocked until a separately reviewed, reversible restore/write executor is validated on hardware.

Remaining Phase 5 work is hardware/integration validation, not missing Android v1 feature implementation.

## Android v1 milestone — 100% software complete ✅

The native Android companion/launcher scope for Phases 0–5 is software-complete. Open work in those phases is explicitly hardware-validation or future post-v1 mutation/remediation work.

## Phase 6 — EchoCore era 🚧

This is the next system layer and is being developed separately from the completed Android v1 milestone.

- `EchoCore.xex`
- first-party Xbox API
- EchoLink hardware PING/PONG proof
- read-only native CORE_INFO / CURRENT_TITLE / FILE_STAT / DIR_LIST / Doctor sources
- pairing/authentication before privileged commands
- real-time events
- EchoHUD
- EchoBoost
- EchoPad
- automations and EchoAI diagnostics

Aurora NOVA / Aurora FTP / FTPdll remain compatibility providers while EchoCore graduates through hardware proof and capability promotion.

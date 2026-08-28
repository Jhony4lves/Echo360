# Echo360 Roadmap

## Phase 0 — Native foundation

- Android app shell in Kotlin + Jetpack Compose
- Game-first EchoHome navigation
- CI build validation
- Architecture and secret-handling rules

## Phase 1 — Native connectivity

- Xbox endpoint configuration
- Secure local credential storage
- Console discovery / reachability status
- Aurora NOVA client
- Aurora FTP client
- FTPdll active-mode client
- Shared path normalization (`Hdd1:` ↔ FTP namespace)

## Phase 2 — EchoTransfer parity

- Graphical local/Xbox folder picker
- Smart compare by relative path + size
- Missing/different plan
- Fast / Background / Auto
- Session reuse and graceful QUIT
- Retry and failover
- Progress, speed and ETA
- Post-upload remote SIZE verification
- Transfer history

## Phase 3 — EchoHome + EchoLibrary

- Installed-game discovery
- Covers and metadata cache
- Continue playing
- Recent / favorites / backlog
- Filters: Kinect, local multiplayer, genre and status
- Per-game launch screen

## Phase 4 — EchoDoctor

- Game integrity findings
- TU / Media ID diagnostics
- Plugin/config checks
- Crash context and safe-launch recommendations

## Phase 5 — Deeper integration

- EchoRemote
- EchoSync / save vault
- EchoIntegrity hashes
- EchoStats / sessions
- EchoMods / EchoTU

## Phase 6 — EchoCore era

- `EchoCore.xex`
- first-party Xbox API
- real-time events
- EchoHUD
- EchoBoost
- EchoPad
- automations and EchoAI diagnostics

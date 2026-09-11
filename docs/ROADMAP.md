# Echo360 Roadmap

## Status legend

- ✅ **Software complete** — implemented in the native Android app and covered by CI; target-console validation may still be pending.
- 🧪 **Hardware pending** — the software path exists, but the behavior still needs proof on the target Xbox 360 / Galaxy S24 setup.
- 🚧 **Active** — implementation belongs to the next project layer and is still evolving.
- 🗃️ **Archived** — preserved only as technical history/reference; not part of the active product architecture.

## Phase 0 — Native foundation ✅

- Android app shell in Kotlin + Jetpack Compose
- Game-first navigation
- CI build validation
- Architecture and secret-handling rules

## Phase 1 — Native connectivity ✅

- Xbox endpoint configuration
- Secure local credential storage
- Console discovery / reachability status
- Aurora NOVA client
- Aurora passive FTP client
- FTPdll active-mode client
- Shared path normalization (`Hdd1` ↔ provider namespace)

## Phase 2 — EchoTransfer ✅ software / 🧪 hardware

- Graphical local/Xbox folder picker
- Smart compare by relative path + size
- Missing/different plan
- Aurora / FTPdll / Auto
- Measured-throughput Auto router
- Session reuse and graceful QUIT
- Bounded retry and failover
- Progress, speed and ETA
- Post-upload remote SIZE verification
- Persistent transfer history

Remaining work is target-hardware validation of long transfers, retries, cancellation, failover and real throughput behavior.

## Phase 3 — EchoHome + EchoLibrary ✅ software / 🧪 hardware

- Installed-game discovery from Aurora data
- Artwork/background cache
- Continue Playing
- Recent / favorites / backlog
- Game metadata and filters
- Per-game launch surfaces
- Play-session timeline and observed playtime
- Launch-readiness and integrity summaries

## Phase 4 — EchoDoctor + EchoIntegrity ✅ software / 🧪 hardware

- Game integrity findings
- TU / Media ID runtime diagnostics
- DashLaunch plugin/config diagnostics
- RAM / temperature / storage telemetry where providers expose evidence
- Unified full scan
- Crash context and safe-launch recommendations

No resident EchoCore source is required for Phase 4.

## Phase 5 — EchoSync / EchoRemote / EchoTU ✅ software v1 / 🧪 hardware

- EchoStats / sessions
- Save Vault read-only Xbox → Android snapshots
- Vault SHA-256 verification
- Documented NOVA controls
- Restricted remote console actions
- Evidence-first TU inventory
- Mutation safety gate requiring verified rollback coverage before future writes

## Phase 6 — EchoFix 🚧

### Rule 001 — exposed installer payload ✅ software / 🧪 hardware

Detect and repair misplaced `content/0000000000000000/FFED2000/FFFFFFFF` payloads using STFS metadata, server-side move when possible, conflict blocking and post-operation `SIZE` verification.

### Rule 002 — installer trapped inside GOD ✅ software / 🧪 hardware

- Detect GOD containers under `00007000`
- Reconstruct a temporary XISO on Android from `DataNNNN`
- Remove GOD hash metadata while streaming
- Parse XDVDFS
- Locate `content/0000000000000000/FFED2000/FFFFFFFF`
- Route embedded STFS payloads by real Title ID / Content Type
- Upload only missing packages
- Keep the original GOD untouched
- Clean temporary cache after successful repair

Next optimization target: reduce or eliminate full temporary-XISO reconstruction for cases where bounded/random access can safely recover only the required content.

## Archived experiment — EchoCore / EchoLink 🗃️

Resident EchoCore / EchoLink development is no longer an active roadmap phase. Hardware experiments showed black-screen/runtime fragility and did not justify making Echo360 depend on a resident custom service.

Historical XEX, protocol and host-reference material may stay in the repository for research. It is excluded from the active Android APK and CI.

Any future Xbox-side helper must start as optional, on-demand, manually recoverable code and prove stability on the real console before architecture changes are considered.

## Release gate

A new APK can be promoted when:

1. debug and release builds succeed;
2. release signing verifies;
3. all unit/integration tests pass;
4. no retired EchoCore/EchoLink code is included in the Android source set;
5. the relevant real-hardware validation plan is documented.

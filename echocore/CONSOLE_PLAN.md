# EchoCore console runtime plan

EchoCore is the Xbox-side runtime of Echo360. This directory is intentionally isolated from the Android app so console work can evolve independently and safely.

## Ground rules

1. Build and test a manually launched `EchoCore.xex` before any DashLaunch auto-load plugin.
2. Keep bootstrap operations read-only and non-destructive until pairing/authentication is implemented.
3. Never expose CPU key, DVD key, console ID, serials, NAND contents or arbitrary memory access through EchoLink.
4. Treat game memory patches as exact-build, opt-in profiles. Unknown executable/TU fingerprints are never patched.
5. EchoCore must yield resources to the active game. Background work is throttled or paused when gameplay would be affected.
6. Every risky patch must have expected-original-byte guards and a rollback path.

## Runtime modules

### EchoLink

First-party LAN protocol and service lifecycle.

Near-term:
- TCP control service on port 36000.
- bounded frame parser and fail-closed validation.
- PING/PONG hardware proof.
- repeated sessions/reconnect after the one-shot bootstrap is proven.
- HELLO/CAPS with protocol/Core version and non-sensitive capability flags.
- pairing/authentication before any write, launch, reboot or patch command.
- optional UDP discovery beacon after the TCP service is stable.

### EchoTransfer

Native Xbox file transport intended to replace FTP as the preferred path while keeping Aurora/FTPdll as fallbacks.

Design:
- persistent binary data connection rather than FTP active/passive negotiation.
- fixed-size bounded chunks with explicit offset and length.
- temporary destination file, verification, then finalize.
- resumable transfers using verified offsets.
- sequential disk writes with a small bounded buffer queue and backpressure.
- adaptive chunk/window sizing from measured network and disk throughput.
- dashboard/full-speed mode and gameplay/throttled mode.
- post-transfer size/hash verification.
- no destructive remote delete in early versions.

The first benchmarks should compare EchoTransfer against the already validated Aurora passive and FTPdll active paths on the same LAN and file set.

### EchoDoctor

Read-only health and diagnostics service.

Planned signals:
- current Title ID and executable/module identity.
- kernel/dashboard version.
- mounted storage and free space.
- EchoCore network counters and transfer errors.
- safe temperature/fan telemetry where supported by verified APIs.
- loaded-plugin/configuration health where it can be observed safely.
- lightweight crash breadcrumbs for EchoCore itself.

No unique console secrets are part of telemetry.

### EchoIO

Resource governor shared by transfers, hashing, telemetry and future background jobs.

Policies:
- low-priority/background worker threads.
- bounded memory usage.
- reduce or pause disk/network work while a game is active when contention is detected.
- never improve transfer benchmark numbers by stealing frame time from a game.

### EchoBoost

Per-title performance patch framework. It is not a universal FPS unlock.

A profile is eligible only when all required identity checks match, for example:
- Title ID.
- Media/build/TU identity when available.
- executable/module timestamp, size or hash/fingerprint.
- expected original bytes at every patch site.

Profile operations may later include known-safe frame-cap, frame-pacing, VSync or title-specific performance patches. Every applied patch records original bytes and is reverted when the title/module unloads or when the profile is disabled.

Unknown builds fail closed and remain unpatched.

### EchoHUD

Later-stage optional overlay for FPS/frame time, temperature and network/transfer state. It stays out of the bootstrap because rendering hooks increase compatibility risk and can themselves cost performance.

## Delivery ladder

### C0 — reproducible XEX
- minimal OpenXeChain build path.
- freestanding EchoCore bootstrap linked only to Xbox import stubs.
- CI publishes `EchoCore.xex`.

### C1 — hardware EchoLink proof
- manual launch from Aurora/file manager.
- repeated cold launches.
- Android/Termux probe receives valid PONG from Xbox port 36000.
- malformed frames close the connection without crashing the console.

### C2 — resident service
- multiple requests and reconnects.
- controlled worker thread/service lifetime.
- manual plugin load/unload experiments before DashLaunch boot registration.
- watchdog and clean shutdown.

### C3 — read-only console API
- HELLO/CAPS.
- storage list/stat.
- title/runtime identity.
- Doctor telemetry.

### C4 — paired native transfer
- pairing/authentication.
- safe temporary upload path.
- chunk/resume/verify/finalize.
- benchmark against Aurora and FTPdll.

### C5 — resident DashLaunch plugin
- boot-safe plugin packaging.
- conservative base-address/module-collision strategy.
- recovery path that never requires NAND changes.

### C6 — EchoBoost profiles
- patch engine with exact-build guards and rollback.
- first profile only after title-specific research and hardware measurement.
- no blind/global memory patching.

### C7 — EchoHUD and advanced automations
- overlay and real-time events.
- optional performance/session instrumentation.

## Current blocker being addressed

The original full OpenXeChain CI successfully built LLVM/Clang and `xecorelib`, then failed while configuring Newlib. The current EchoCore bootstrap does not use heap/libc and therefore does not need Newlib. The new minimal toolchain path deliberately builds only:

1. Xbox-target LLVM/Clang/LLD;
2. `xecorelib` import stubs;
3. host-side SynthXEX;
4. freestanding EchoCore PE -> XEX2 conversion.

This keeps the first console artifact as small and debuggable as possible.

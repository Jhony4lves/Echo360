# EchoCore bootstrap strategy — ARCHIVED

> **Status: retired experiment.** This document is preserved only as technical history. EchoCore is not part of the active Echo360 architecture, Android APK or CI, and the Xbox does not need a resident EchoCore service.

## Why this experiment was retired

The project tested the resident-XEX direction on the real Xbox 360 RGH. In practice it introduced black-screen/runtime fragility and unreliable service availability. Aurora NOVA + Aurora FTP + FTPdll already provide the connectivity needed by the active Echo360 design without adding a boot/runtime dependency.

The current architecture therefore keeps repair, transfer, diagnostics and automation outside the console whenever possible.

## Historical goal

The original goal was to run the smallest possible Echo-owned service on an RGH/JTAG Xbox 360 and prove direct Android ↔ Xbox communication with an EchoLink `PING/PONG` exchange.

The experiment intentionally avoided NAND writes, arbitrary memory access, console secrets and privileged remote commands.

## Historical toolchain notes

### LibXenon

LibXenon is useful open-source Xbox 360 homebrew infrastructure, but it is a bare-metal environment and did not match the original goal of a service coexisting with the normal Xbox OS/Aurora/DashLaunch environment.

### OpenXeChain / SynthXEX

OpenXeChain/SynthXEX was investigated as an open toolchain for producing Xbox-OS XEX2 executables. The experiment was useful for research but is no longer an active product dependency.

## Historical development ladder

1. Host-side protocol proof.
2. Minimal manually launched Xbox executable.
3. Repeated hardware ping/reconnect validation.
4. Only after stability, consider any deeper integration.

The project **does not proceed to step 4 under the current architecture**.

## Current rule for any future Xbox-side helper

If an Xbox-side component is revisited later, it must:

- be optional and on-demand first;
- never be required for console boot;
- have a trivial recovery path;
- avoid NAND writes and console-unique secrets;
- prove repeated stability on the real console before wider use;
- provide a capability that Aurora/FTPDll cannot reasonably provide externally.

Until those conditions are met, the source of truth is `docs/ARCHITECTURE.md`: Aurora NOVA, Aurora FTP and FTPdll are the active connectivity stack.

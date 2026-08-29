# EchoCore bootstrap strategy

## Goal

Run the smallest possible Echo-owned service on an RGH/JTAG Xbox 360 and prove direct Android <-> Xbox communication with EchoLink `PING/PONG`.

The first Xbox build must not write files, patch memory, touch NAND, expose unique console secrets, launch titles or reboot the system.

## Toolchain decision

### Not the primary path: LibXenon

LibXenon is valuable, legal, open-source Xbox 360 homebrew infrastructure, but it is a **bare-metal** environment. EchoCore's target architecture is a service that coexists with the normal Xbox 360 OS/Aurora/DashLaunch environment, so bare-metal homebrew is not the preferred first path.

Reference:
- https://github.com/Free60Project/libxenon

### Preferred experiment: OpenXeChain / SynthXEX

OpenXeChain describes itself as a modern open-source LLVM-based Xbox 360 toolchain. Its SynthXEX component is intended to build XEX2 executables for the Xbox 360 OS without relying on the official proprietary SDK.

This is attractive for EchoCore because the project goal matches ours: create Xbox-OS applications from an open toolchain.

Caveat: the toolchain is young and SynthXEX explicitly describes missing features. We therefore treat it as an experiment until a minimal XEX is successfully built and launched on the target console.

References:
- https://github.com/OpenXeChain
- https://github.com/OpenXeChain/SynthXEX

## Development ladder

### A. Host protocol proof

- Kotlin EchoLink codec/client.
- Portable C reference server on Linux.
- Cross-language `PING/PONG` in CI.

This proves the wire format independently of Xbox-specific tooling.

### B. Minimal Xbox executable

The first Xbox artifact should do only:

1. initialize the network API available to the chosen open toolchain/runtime;
2. bind TCP port `36000` on LAN;
3. accept a connection;
4. parse one bounded EchoLink v1 frame;
5. answer `PONG` to `PING`;
6. survive malformed input by closing the client connection rather than crashing the console.

### C. Hardware proof

On the target RGH console:

- launch the minimal build manually first;
- do **not** install it as an automatic DashLaunch plugin on the first test;
- confirm repeated ping/reconnect cycles;
- only after stability is demonstrated consider resident/plugin behavior.

## Safety policy

- No NAND writes.
- No delete/RMD equivalent in bootstrap.
- No CPU key, DVD key, Console ID or serial APIs.
- No arbitrary memory read/write.
- No remote launch/reboot before authenticated pairing exists.
- All network lengths are validated before allocation/use.
- Hardware behavior is never marked validated from CI alone.

## Why a manual executable first

An auto-loaded plugin has a much larger blast radius: a startup crash can interfere with every boot. A manually launched `EchoCore.xex` lets us prove networking and stability while keeping recovery trivial. Resident loading comes later.

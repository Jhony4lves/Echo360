# EchoCore OpenXeChain bootstrap

This directory contains the first Xbox 360 OS build of EchoCore.

## Why the toolchain is intentionally minimal

The original full OpenXeChain probe successfully built and installed the Xbox-target LLVM/Clang toolchain and `xecorelib`, then failed while configuring Newlib. The current EchoCore bootstrap is freestanding C: it does not allocate heap memory or use libc APIs. Its only external calls are Xbox OS network imports resolved by `xecorelib`.

`build-minimal-toolchain.sh` therefore builds only the components needed for this bootstrap:

- Xbox-target LLVM/Clang/LLD;
- `xecorelib` import stubs;
- SynthXEX as a host tool.

It intentionally skips Newlib and compiler-rt.

## Build contract

The CI pins the OpenXeChain buildscript revision and initializes only its `llvm`, `xecorelib` and `synthxex` submodules. A successful toolchain is cached and then used by `build-echo-core.sh`.

`build-echo-core.sh` produces:

- `/tmp/echocore-build/EchoCore.pe`
- `/tmp/echocore-build/EchoCore.xex`

The script validates the `MZ` PE signature and `XEX2` output signature before CI publishes the XEX artifact.

## Hardware test boundary

The current XEX must be launched manually. It:

1. starts Xbox networking;
2. binds TCP `0.0.0.0:36000`;
3. accepts one client;
4. accepts one bounded EchoLink v1 `PING`;
5. returns the matching `PONG`;
6. closes sockets and returns to the loader.

It does not write files, launch titles, reboot, access NAND, expose console secrets or provide arbitrary memory operations.

Do not register it as a DashLaunch boot plugin until the manual hardware proof is stable.

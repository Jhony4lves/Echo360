# EchoLink protocol — ARCHIVED

> **Status: retired experiment.** EchoLink is not part of the active Android APK or current Xbox connectivity architecture. This document is preserved only as a protocol/reference note for the former EchoCore experiment.

The active Echo360 stack uses Aurora NOVA, Aurora passive FTP and FTPdll active FTP. Port `36000` and EchoLink `PING/PONG` are not required for normal operation.

## Historical transport

- TCP over the local network.
- Default experimental port: `36000`.
- Network byte order (big-endian) for multi-byte integers.
- The bootstrap protocol was LAN-only and unauthenticated and therefore intentionally prohibited filesystem writes, launch, reboot, memory, keys or privileged commands.

## Historical frame header — v1

Every frame started with 16 bytes:

| Offset | Size | Field | v1 |
| ---: | ---: | --- | --- |
| 0 | 4 | magic | ASCII `ECHO` / `0x4543484F` |
| 4 | 1 | version | `1` |
| 5 | 1 | type | command/response type |
| 6 | 2 | flags | `0` unless defined later |
| 8 | 4 | payloadLength | unsigned logical length, bounded before allocation |
| 12 | 4 | requestId | echoed by the response |

Control payloads were limited to 1 MiB.

## Bootstrap frame types

- `0x01 PING`
- `0x02 PONG`
- `0x7F ERROR`

`PING` carried an opaque 64-bit nonce. `PONG` echoed the same request ID and nonce so the client could reject stale/cross-wired responses and measure latency.

## Fail-closed rules

The prototype was designed to close the connection on bad magic, unsupported version, unknown frame type, oversized payload, incomplete frame or malformed command payload.

## Current status

No active product capability depends on this protocol. Historical host/Xbox reference material may remain in the repository for research, but it must not be treated as a release dependency or a reason to load resident code on the console.

If direct Xbox-side networking is reconsidered in the future, a new proposal should start from the current architecture and hardware evidence rather than assuming this protocol or port is still the preferred design.

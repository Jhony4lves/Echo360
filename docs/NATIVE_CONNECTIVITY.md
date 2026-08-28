# Native Connectivity

This document records the first Android-native transport contracts for Echo360.

## Canonical paths

The rest of the app uses canonical Xbox paths such as:

- `/Hdd1/Games`
- `/Usb0/Content`
- `/Flash`

Transport-specific translation happens at the edge.

Aurora FTP keeps canonical drive names. FTPdll currently exposes the confirmed namespace:

- `Hdd1` -> `fHdd`
- `Usb0` -> `fUsb0`
- `Flash` -> `fFlash`

Unknown drives fail explicitly instead of silently targeting the wrong directory.

## Connection checks

NOVA: current native client performs a TCP reachability check only. It intentionally avoids identity-sensitive endpoints while the authenticated NOVA response contracts are being normalized.

Aurora FTP and FTPdll: the native control client performs `USER` / `PASS`, reports normalized auth/busy/network states and sends `QUIT` before closing.

No password is included in errors or logs.

## Next transport step

EchoTransfer will extend these primitives with:

- Aurora passive data connections;
- FTPdll active-mode data connections;
- LIST/CWD/SIZE/MKD/STOR;
- connection reuse;
- progress and cancellation;
- Fast -> Background failover.

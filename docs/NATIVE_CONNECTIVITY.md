# Native Connectivity

This document records the Android-native transport contracts for Echo360.

## Canonical paths

The rest of the app uses canonical Xbox paths such as:

- `/Hdd1/Games`
- `/Usb0/Content`
- `/Flash`

Transport-specific translation happens only at the edge.

Aurora FTP keeps canonical drive names. FTPdll currently exposes the confirmed namespace:

- `Hdd1` -> `fHdd`
- `Usb0` -> `fUsb0`
- `Flash` -> `fFlash`

Unknown FTPdll drives fail explicitly instead of silently targeting the wrong directory.

## Connection checks

NOVA: the current native client performs a TCP reachability check only. It intentionally avoids identity-sensitive endpoints while authenticated NOVA response contracts are normalized.

Aurora FTP and FTPdll: the native control channel performs `USER` / `PASS`, switches to `TYPE I`, reports normalized auth/busy/network states and sends `QUIT` before closing.

No password is included in errors or logs.

## Native data sessions

### Fast — Aurora

`AuroraPassiveFtpSession` reuses one authenticated control connection and uses passive data sockets. It tries `EPSV` first and falls back to `PASV`. A `0.0.0.0` PASV address is replaced with the control-channel peer address.

Deep listings intentionally use `CWD` followed by bare `LIST`, matching the behavior validated against Aurora.

### Background — FTPdll

`FtpDllActiveFtpSession` reuses one authenticated control connection. For every data operation it opens a local IPv4 listener, sends `PORT`, waits for the Xbox to connect back, then performs the data transfer.

This matches the active-mode behavior validated against FTPdll on the target console.

### Normalized operations

Both sessions expose the same contract:

- `list`
- `size`
- `ensureDirectory`
- `upload`
- `close`

Neither session implements destructive delete operations.

## Routing

`XboxFtpSessionFactory` exposes:

- `Fast` -> Aurora passive FTP
- `Background` -> FTPdll active FTP
- `Auto` -> Fast first, then Background if Fast cannot establish a session

The EchoTransfer layer will add comparison plans, queue state, retries, speed/ETA, post-upload verification and in-job failover on top of these sessions.

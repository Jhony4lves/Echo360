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

If a full FTP check fails, the UI runs a separate minimal TCP probe. This distinguishes an Android routing failure from a protocol/control-channel failure without doubling the number of connections during successful checks.

No password is included in errors or logs.

### Hardware validation — 2026-09-01

The native implementation was validated against the target Xbox 360 without rebooting the console:

- NOVA responded on port `9999`.
- Aurora authenticated on port `21`, negotiated passive mode with `PASV` and listed the remote root.
- FTPdll authenticated on port `7564`, negotiated active mode with `PORT` and listed the remote root.
- More than ten consecutive **Save and test** cycles completed successfully for all three transports.

The recurring pattern where FTP worked once and later timed out was traced to failed control-channel setup. `FtpCommandChannel.connectAndLogin()` could throw before a session object reached the repository, leaving that partially initialized socket outside the repository's cleanup path. The channel now closes its streams and socket on every setup failure. A regression test verifies that the server observes EOF after a rejected login.

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

The EchoTransfer layer adds comparison plans, queue state, retries, speed/ETA, post-upload verification and in-job failover on top of these sessions.

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
- `delete`
- `upload`
- `download`
- `close`

`delete` is intentionally narrow and is currently used by the automatic FTP benchmark to remove Echo360-owned probe files. Player-facing code does not receive an arbitrary remote delete surface.

## Routing

`XboxFtpSessionFactory` exposes:

- `Fast` -> Aurora passive FTP
- `Background` -> FTPdll active FTP
- `Auto` -> benchmark both healthy physical providers and select the higher measured upload throughput

`FtpAutoRouter` performs the Auto benchmark immediately before a mutating transfer begins. It uploads a 2 MiB probe through each available provider, verifies the remote size, and best-effort deletes the probe afterward.

The benchmark uses canonical paths under:

- `/Hdd1/Echo360/.bench/aurora.probe`
- `/Hdd1/Echo360/.bench/ftpdll.probe`

The FTPdll adapter translates those paths to its `fHdd` namespace. Fixed probe names intentionally bound possible leftovers to at most two small files if a server rejects `DELE`.

The winning route is cached for five minutes. A healthy cached winner is reconnected without repeating the benchmark. If that connection fails, the cache is discarded and Auto measures the available providers again.

EchoTransfer analysis and remote browsing remain read-only: they do not run the upload benchmark. Auto analysis currently tries Aurora for read-only scanning and falls back to FTPdll if Aurora is unavailable. The throughput decision is deferred until `execute()` because that is the point where the user has already requested a remote mutation.

During an Auto transfer, transient failures are retried on the current physical provider. After bounded retries are exhausted, EchoTransfer may fail over once to the other provider in either direction:

- Aurora FTP -> FTPdll
- FTPdll -> Aurora FTP

The current file restarts from byte zero after failover. Completed and verified files are not resent. Resume support is intentionally not claimed yet.

Every successful upload is verified with remote `SIZE` before EchoTransfer counts the file as complete.

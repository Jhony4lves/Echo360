# Legacy Companion

The Node/PWA Companion is a reference implementation during the native migration.

Validated behavior that must be preserved includes:

- Aurora FTP on port 21 using passive transfers
- FTPdll background route on port 7564 using active FTP
- Xbox canonical path translation for FTPdll (`Hdd1` → `fHdd`, `Usb0` → `fUsb0`, `Flash` → `fFlash`)
- smart comparison by relative path and file size
- Fast / Background / Auto routing
- transfer progress, speed and ETA
- session cleanup and Aurora connection-limit handling
- post-upload size verification

Real credentials and console-unique values are intentionally excluded from this document and from source control.

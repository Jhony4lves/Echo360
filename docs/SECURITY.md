# Echo360 Security Model

## Principles

- Real Xbox credentials never belong in Git.
- The Android app stores the Xbox profile encrypted with AES-GCM.
- The encryption key is generated and retained by Android Keystore.
- App backup is disabled so an encrypted preference blob is not restored without its device-bound key.
- Passwords are never included in domain `toString()` output or FTP error messages.
- The app does not query or display unique console identity secrets during normal connectivity checks.

## Local-network trust boundary

Aurora, NOVA and FTPdll are legacy local-network services and do not provide modern end-to-end transport security. Echo360 therefore treats them as LAN-only transports.

Do not expose the Xbox FTP/NOVA ports directly to the public internet.

A future EchoCore/EchoPair protocol should replace long-lived shared passwords with device pairing and a first-party authenticated API.

## Source control

The repository may contain example ports and placeholder configuration only. Files such as `config.json`, `.env`, keystores and local runtime data remain ignored.

## Destructive operations

Read-only diagnostics and non-destructive transfer behavior remain the default. Any future remediation that deletes or replaces remote data must have explicit scope, verification and a rollback strategy.

# Security

Echo360 interacts with modified Xbox 360 consoles and may handle credentials used by local services such as FTP or console APIs.

## Never commit

- FTP usernames/passwords from a real console
- NOVA/API tokens or JWTs
- CPU keys, DVD keys, console IDs, serials or NAND-derived secrets
- Android signing keys
- Personal save/profile backups

Use local configuration or platform-secure storage for secrets. Repository examples must contain placeholders only.

## Reporting

Until a dedicated security contact is configured, open a private repository issue describing the problem without including console-unique secrets.

# Echo360 Architecture

## Product shape

Echo360 is a native Android management layer for the user's Xbox 360 RGH. The active design deliberately keeps the Android app independent from any resident custom XEX.

```text
Compose UI
   ↓
Feature modules
   ↓
Domain / use cases
   ↓
Xbox gateway interfaces
   ↓
Aurora NOVA | Aurora FTP | FTPdll
```

The UI must never know which FTP implementation is carrying a transfer. It requests a transfer policy; the transport layer selects and verifies the concrete provider.

## Active transport policy

- Aurora passive FTP and FTPdll active FTP are the supported file transports.
- `Auto` measures real transfer throughput and can fail over at safe boundaries.
- Aurora NOVA is used only for capabilities it actually exposes.
- Canonical paths use Xbox-style names such as `/Hdd1/...`; provider adapters translate to transport-specific namespaces such as `/fHdd/...` when required.
- File repair, diagnostics and automation live outside the console whenever possible.

## Resident Xbox code

The earlier EchoCore / EchoLink resident-service direction is **retired from the active architecture**. Real-console experiments produced black-screen/runtime fragility and closed-service behavior that made a resident dependency a worse tradeoff than the already working Aurora/FTPDll stack.

Historical source and protocol notes may remain in the repository for research. They are not part of the APK or active CI and are not required for Echo360 to work.

A future Xbox-side helper may be reconsidered only as an optional, on-demand component after isolated hardware proof. It must never become a boot requirement without a separately reviewed stability case.

## Boundaries

### `app`
Android entry point, navigation, dependency assembly and top-level design system.

### Home / Library
Game-first launcher surfaces, catalog, metadata, sessions and launch actions.

### Transfer
Compare, transfer plan, progress, retry, verification, history and Aurora/FTPDll routing.

### Doctor / Integrity / Fix
Evidence-first diagnostics, content integrity and repair recipes. Repairs must expose a plan before mutation and verify destinations after mutation.

### Sync / Vault
Read-only snapshot and local integrity features.

### Network
Connection policies, timeouts, discovery, NOVA and FTP provider implementations.

## Credential policy

Real credentials and console-unique secrets are never source-controlled. Android stores connection configuration locally and privately.

## Migration rule

A capability is considered migrated only after the native implementation is validated against the real Xbox hardware. CI proves software behavior, not console compatibility.

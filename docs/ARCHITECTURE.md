# Echo360 Architecture

## Product shape

Echo360 is moving to a native Android app with Xbox-specific transports hidden behind domain interfaces.

```text
Compose UI
   ↓
Feature modules
   ↓
Domain / use cases
   ↓
Xbox gateway interfaces
   ↓
Aurora NOVA | Aurora FTP | FTPdll | future EchoCore
```

The UI must never know whether a file transfer is using Aurora FTP or FTPdll. It requests a transfer policy; the transport layer decides how to satisfy it.

## Planned boundaries

### `app`
Android entry point, navigation, dependency assembly and top-level design system.

### `feature:home`
Game-first launcher surface: continue playing, recent games, console state and quick actions.

### `feature:library`
Game catalog, metadata, filters, favorites, sessions and launch actions.

### `feature:transfer`
Compare, transfer plan, progress, retry, verification, history and Fast/Background/Auto routing.

### `feature:doctor`
Rules-based diagnostics that consume normalized console/game state instead of raw FTP responses.

### `core:model`
Stable models such as Xbox status, game identity, storage entries, transfer plan and diagnostic finding.

### `core:network`
Connection policies, timeouts, discovery and shared network primitives.

### `protocol:aurora`
NOVA HTTP/JWT and Aurora FTP behavior.

### `protocol:ftpdll`
FTPdll active-mode behavior and Xbox path translation.

### `protocol:echocore`
Future first-party protocol used once `EchoCore.xex` exists.

## Credential policy

Real credentials are never source-controlled. During early migration they remain local-only. Before the native protocol milestone, Android credentials move to encrypted/private app storage.

## Migration rule

A legacy Companion capability is considered migrated only after the native implementation is validated against real Xbox hardware.

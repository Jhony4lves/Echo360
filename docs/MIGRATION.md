# Companion → Native Android Migration

The existing Echo360 Companion remains the behavioral reference while features are migrated to native Android modules.

## Migration order

1. Android shell + navigation + connection status
2. Xbox endpoint configuration and secure local credential storage
3. EchoTransfer comparison engine and queue
4. Aurora/NOVA telemetry and remote actions
5. EchoHome / EchoLibrary
6. EchoDoctor diagnostics
7. Background jobs, notifications and persistence
8. Deprecate the legacy Node/PWA runtime after feature parity

## Compatibility rule

A working Companion capability is not removed until an equivalent native path is validated against real hardware.

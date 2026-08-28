# Echo360 Project Principles

1. **Game-first UX.** Technical power should stay available without making the player think like a file manager.
2. **Native Android first.** Kotlin + Jetpack Compose is the product direction; the legacy Companion remains a migration reference.
3. **Safe by default.** Read before write, verify after transfer, snapshot before risky changes.
4. **Protocol abstraction.** Aurora, FTPdll and future EchoCore are transport implementations, not UI concerns.
5. **No fake performance promises.** Patches must be tied to known game/build identities and support rollback.
6. **Offline-friendly.** Core console management should work on the local network without depending on cloud services.
7. **No secrets in source control.** Console-unique data stays local.
8. **Modular growth.** EchoHome, Transfer, Doctor, Remote and future modules must remain separable and testable.

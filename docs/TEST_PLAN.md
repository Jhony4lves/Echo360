# Native Hardware Validation Plan

The Android app is developed offline-first, but protocol milestones are not considered complete until tested against real hardware.

## Foundation

- app installs and opens on Android
- bottom navigation works
- no secrets are bundled in the APK

## Aurora / Fast

- authenticate once
- list `Hdd1/Games`
- navigate repeatedly without leaking sessions
- upload a small disposable file
- verify remote size
- run a multi-file queue using one control session when possible

## FTPdll / Background

- active-mode login
- path translation (`Hdd1` to `fHdd`)
- list / upload / SIZE
- transfer continues when Aurora is unavailable

## Auto

- start on Aurora
- make Aurora unavailable by launching a game
- verify controlled failover to FTPdll
- never mark a partially uploaded file as successfully verified

## Regression rule

Every protocol bug discovered on hardware should become a repeatable test or explicit invariant in the native implementation.

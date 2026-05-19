# EasyPlanner

**Recurring tasks on your phone.**

```bash
cd crates/easyplanner-app && boltffi pack android
cd ../../app && ./gradlew assembleDebug
```

Work in progress, not a store-ready release.

## What you get

| Piece | Role |
|-------|------|
| Task list | Add, edit, finish, delete; filter open vs done |
| Scheduler | Rust poller marks tasks due, expired, or finished in SQLite |
| Reminders | System notifications when a run fires (API 33+ needs permission) |
| Settings | Light / dark / system theme |
| Persistence | SQLite + migrations in `crates/easyplanner` |

## Repo map

| You want… | Look here |
|-----------|-----------|
| UI, navigation, theme, notifications | `app/` |
| Recurrence, scheduler, store, JNI pack | `crates/` |
| Generated Kotlin + `.so` for Android | `crates/easyplanner-app/dist/android` (after `boltffi pack android`) |

## Prerequisites

- [Rust](https://rustup.rs/) (workspace under `crates/`)
- [BoltFFI](https://github.com/boltffi/boltffi) CLI — `boltffi pack android` from `crates/easyplanner-app`
- Android SDK (API 24+, target 36) — JDK 11+, Gradle via `app/gradlew`

If the Android build cannot find native libs or generated Kotlin, run `boltffi pack android` first (see `app/app/build.gradle.kts`).

## Crates (Rust)

| Crate | Purpose |
|-------|---------|
| `easyplanner` | Calendar/recurrence, SQLite store, task scheduler |
| `easyplanner-app` | BoltFFI Android bindings consumed by the app |

---

[![GitHub](https://img.shields.io/badge/GitHub-denmeh%2Feasyplanner-181717?style=flat&logo=github)](https://github.com/denmeh/easyplanner)

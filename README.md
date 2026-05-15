# EasyPlanner

**A small Android app with a Rust brain** — for fun, and to see how much of the real work can live in Rust while Compose stays thin.

```bash
cd crates/easyplanner-app && boltffi pack android
cd ../../app && ./gradlew assembleDebug
```

Work in progress; this is not a polished release. On Windows, from `app`, run `gradlew.bat assembleDebug` instead of `./gradlew`.

| You want… | Look here |
|-----------|-----------|
| UI, navigation, theme | `app/` |
| Planner logic, DB, JNI pack | `crates/` |

If `app` fails to find native bits, run `boltffi pack android` from `crates/easyplanner-app` first (see comment in `app/app/build.gradle.kts`).

That is enough to clone and poke around. Anything deeper can live in code comments for now.

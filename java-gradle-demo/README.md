# Easyplanner Java / Gradle demo (BoltFFI `dist/java`)

This sample consumes the JVM output produced by BoltFFI under
[`../crates/easyplanner-app/dist/java`](../crates/easyplanner-app/dist/java): generated Java sources, JNI native under `native/windows-x86_64/`, and a small `main` that calls `EasyplannerApp.distance`.

Official packaging notes: [BoltFFI – Java packaging](https://www.boltffi.dev/docs/packaging#java-packaging).

## Prerequisites

- **JDK 17+** (toolchain in `build.gradle.kts`; align with your BoltFFI `targets.java.min_version` if you set one).
- **`JAVA_HOME`** set when you run `boltffi pack java` (BoltFFI prerequisite).
- **BoltFFI output** present after packaging from the Rust crate:

```powershell
cd ..\crates\easyplanner-app
boltffi pack java --release
```

If `dist/java` is missing, Gradle fails at compile time with a short message pointing here.

## Run

```powershell
cd java-gradle-demo
.\gradlew.bat run
```

Expected line: `EasyplannerApp.distance = 5.0`.

## JAR (classes + bundled native)

```powershell
.\gradlew.bat jar
```

Artifact: `build/libs/easyplanner-java-demo.jar`. Native DLLs from `dist/java/native/windows-x86_64/` are packaged under `native/windows-x86_64/` inside the JAR (same layout BoltFFI uses for desktop resource loading).

Run:

```powershell
java -jar build\libs\easyplanner-java-demo.jar
```

## External native (no DLL inside the JAR)

If you ship the JNI DLL next to the app instead of inside the JAR, skip or remove the `processResources` copy in `build.gradle.kts` and run with:

```powershell
java -Djava.library.path=path\to\dist\java\native\windows-x86_64 -cp build\libs\easyplanner-java-demo.jar com.example.easyplanner_javademo.Main
```

Gradle tests can use the same idea:

```kotlin
tasks.test {
    jvmArgs("-Djava.library.path=...")
}
```

## More host targets

If you add JVM `host_targets` in `boltffi.toml`, extend `processResources` in `build.gradle.kts` to copy each `native/<host-target>/` directory you need.

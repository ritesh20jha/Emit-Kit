# EmitKit backend

Gradle multi-module modular monolith: Java 21, Spring Boot 4, Gradle Kotlin DSL.
Root package `dev.emitkit`.

Modules: `common`, `domain`, `vcs`, `spec-engine`, `generators/core`, `ai`, `api-app`, `worker-app`, `architecture-tests`.

## Build and test

Run all commands from `backend/`:

```sh
./gradlew build          # compile, test and run checks (includes Spotless)
./gradlew test           # unit tests in all modules
./gradlew spotlessCheck  # verify google-java-format
./gradlew spotlessApply  # reformat Java sources
./gradlew projects       # list modules
```

Gradle needs JDK 21 for the toolchain. If it is missing, Gradle provisions it through the foojay resolver.

## Layout

- `build-logic/`: convention plugins `emitkit.java-conventions` (Java 21, JUnit 5, AssertJ, Spotless) and `emitkit.spring-app` (Spring Boot).
- `gradle/libs.versions.toml`: version catalog for all dependency and plugin versions.
- `architecture-tests/`: ArchUnit tests that enforce the module dependency rules.

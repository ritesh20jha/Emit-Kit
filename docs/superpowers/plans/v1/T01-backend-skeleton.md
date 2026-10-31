# T01 · Backend monorepo skeleton

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the Gradle multi-module backend with Java 21, Spring Boot 4, Spotless and ArchUnit rules that enforce the module dependency rules.

**Lane:** PLAT · **Depends on:** — · **Size budget:** ~450 changed lines (hard limit 1000) · **Branch:** `ticket/t01`

**Spec:** ov §2 (layout, module dependency rules)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradlew` (+ wrapper files)
- Create: `backend/build-logic/src/main/kotlin/emitkit.java-conventions.gradle.kts` (Java 21 toolchain, JUnit 5, AssertJ, Spotless with google-java-format)
- Create: `backend/build-logic/src/main/kotlin/emitkit.spring-app.gradle.kts` (applies Spring Boot 4 plugin)
- Create: one `build.gradle.kts` + `src/main/java/dev/emitkit/<pkg>/package-info.java` in each of: `common`, `domain`, `vcs`, `spec-engine`, `generators/core`, `ai`, `api-app`, `worker-app`
- Create: `backend/architecture-tests/src/test/java/dev/emitkit/arch/ModuleRules.java`, `ModuleDependencyRulesTest.java`, `ModuleRulesNegativeTest.java`
- Create: `backend/architecture-tests/src/test/java/fixtures/bad/common/BadCommon.java`, `fixtures/bad/api/SomeController.java`
- Create: `backend/.gitignore`, `backend/README.md` (how to build/test, 20 lines)

## Interfaces

- **Produces:** module names above; package roots from the README conventions; version catalog aliases `spring-boot`, `archunit`, `junit-jupiter`, `assertj`, `testcontainers-bom`; convention plugin ids `emitkit.java-conventions`, `emitkit.spring-app`.
- **Module dependencies (exact, `build.gradle.kts`):** `domain → common`; `spec-engine → common`; `generators/core → spec-engine, common`; `vcs → common`; `ai → common, generators/core`; `api-app → domain, vcs, spec-engine, common, ai`; `worker-app → common, domain, vcs, spec-engine, generators/core, ai`.

## Tasks

### Task 1: Gradle build with all modules

- [ ] **Step 1: Write the convention plugins, settings and version catalog.** Spring Boot 4 BOM via `platform(libs.spring.boot.bom)` in `emitkit.java-conventions`; `spec-engine` and `generators/core` must **not** apply `emitkit.spring-app`.
- [ ] **Step 2: Verify the build**

  Run: `./gradlew projects build`
  Expected: lists the 8 modules + `architecture-tests`; `BUILD SUCCESSFUL`.
- [ ] **Step 3: Verify Spotless**

  Run: `./gradlew spotlessCheck`
  Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit** — `git commit -m "build: gradle multi-module skeleton"`

### Task 2: ArchUnit module rules

- [ ] **Step 1: Write the negative test first**

```java
class ModuleRulesNegativeTest {
  @Test
  void commonDependingOnApi_isRejected() {
    JavaClasses classes = new ClassFileImporter().importPackages("fixtures.bad");
    ArchRule rule = ModuleRules.commonIsIndependent("fixtures.bad");
    assertThatThrownBy(() -> rule.check(classes)).isInstanceOf(AssertionError.class)
        .hasMessageContaining("BadCommon");
  }
}
```
  `fixtures.bad.common.BadCommon` has a field of type `fixtures.bad.api.SomeController`.
- [ ] **Step 2: Run it — expect FAIL** (`ModuleRules` doesn't exist).

  Run: `./gradlew :architecture-tests:test --tests '*NegativeTest'`
- [ ] **Step 3: Implement `ModuleRules`** — static factories taking a root package so fixtures can reuse them:
  `commonIsIndependent(root)`, `domainOnlyUsesCommon(root)`, `specHasNoSpring(root)` (no `org.springframework..`), `generatorsUseOnlySpecAndCommon(root)`, `generatorsDoNoIo(root)` (no `java.net..`, `java.nio.file.Files`), `vcsOnlyUsesCommon(root)`, `aiDoesNotUseApps(root)`, `nothingDependsOnApiApp(root)`, `workerDoesNotUseApiApp(root)`. Each is `noClasses().that().resideInAPackage(root + ".x..").should().dependOnClassesThat()...`.
- [ ] **Step 4: Write `ModuleDependencyRulesTest`** with `@AnalyzeClasses(packages = "dev.emitkit")` and one `@ArchTest` field per factory using root `"dev.emitkit"`.
- [ ] **Step 5: Run both — expect PASS**

  Run: `./gradlew :architecture-tests:test`
- [ ] **Step 6: Commit** — `git commit -m "test(arch): enforce module dependency rules"`

---

## Done when

- [ ] All tasks above are committed on `ticket/t01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

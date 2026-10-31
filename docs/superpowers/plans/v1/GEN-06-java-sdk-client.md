# GEN-06 · Java SDK: build, client, exceptions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java SDK: build, client, exceptions: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/gen-06`

**Spec:** v1 §6 table row for this output (dir `{out}/sdk/java/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/java-sdk/`: `src/main/java/dev/emitkit/gen/javasdk/` + `src/main/resources/templates/java-sdk/`
- Templates: `build.gradle.kts.mustache`, `settings.gradle.kts.mustache`, `Client.java.mustache`, `ClientConfig.java.mustache`, `ApiException.java.mustache` (+ subclasses), `custom-package-info.java.mustache`, `README.md.mustache`
- Test: `backend/generators/java-sdk/src/test/java/.../JavaSdkGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `JavaSdkGenerator` (`JAVA_SDK`); base package = `mavenGroupId` + `.` + `NameStrategy.forLanguage(JAVA).member(artifactId)` lowercased without separators.
- `build.gradle.kts`: `java-library`, `group = mavenGroupId`, `version = ctx.version()`, Java 17 release, dep `com.fasterxml.jackson.core:jackson-databind:2.17.x` (a generated SDK targets its users' Jackson 2, not our Boot 4 Jackson 3), `maven-publish` block.
- `{Api}Client` built with `{Api}Client.builder().baseUrl(..).bearerToken(..).apiKey(..).maxRetries(..).httpClient(..).build()`; uses `java.net.http.HttpClient`.
- `ApiException` (status, body) and subclasses `NotFoundException`, `UnauthorizedException`, `ConflictException`, `RateLimitException`, `ServerException`.
- `custom/package-info.java` is `CUSTOM_STUB`.

## Tasks

### Task 1: Golden output

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"petstore", "auth-schemes"})
void matchesGolden(String fixture) {
  GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
}
@Test void outputIsByteStable() {
  var m = FixtureSpecs.load("petstore");
  assertThat(generator.generate(m, ctx())).isEqualTo(generator.generate(m, ctx()));
}
@Test void idIsJAVA_SDK() { assertThat(generator.id()).isEqualTo(GeneratorId.JAVA_SDK); }
@Test void basePackageFromMavenCoords() { /* group "com.acme", artifact "pets-sdk" → src/main/java/com/acme/petssdk/ */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:java-sdk:test`

- [ ] **Step 3: Implement** — `JavaClientView` view-model; models/resources added by GEN-07. First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:java-sdk:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): java sdk: build, client, exceptions"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-06`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

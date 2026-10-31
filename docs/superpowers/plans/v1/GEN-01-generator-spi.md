# GEN-01 · Generator SPI + template engine + golden harness

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define the generator interface, the Mustache template wrapper, the AI hook interface, and a golden-file test harness all generator tickets share.

**Lane:** GEN · **Depends on:** [SPEC-01](SPEC-01-api-model.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/gen-01`

**Spec:** v1 §6 (interface, templates, byte-stable output), ov §2 (generators do no I/O; AI via interface)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/generators/core/src/main/java/dev/emitkit/gen/core/` — `Generator.java`, `GeneratorId.java`, `GeneratedFile.java`, `FileKind.java`, `GeneratorContext.java`, `PackageNames.java`, `DocsAiAssistant.java`, `DocsExamples.java`, `Templates.java`
- Create: `backend/generators/core/src/testFixtures/java/dev/emitkit/gen/core/golden/GoldenFiles.java` (+ `java-test-fixtures` plugin in `build.gradle.kts`)
- Test: `backend/generators/core/src/test/java/dev/emitkit/gen/core/TemplatesTest.java`, `GoldenFilesTest.java`, `src/test/resources/templates/dummy/hello.mustache`

## Interfaces

- **Produces:**
```java
public enum GeneratorId { TS_SDK, PY_SDK, JAVA_SDK, CLI, MCP, DOCS, WORKFLOWS }
public enum FileKind { GENERATED, CUSTOM_STUB }
public record GeneratedFile(String path, String content, FileKind kind) {}   // path relative to repo root, '/' separators
public record PackageNames(@Nullable String npmScope, String npmName, @Nullable String pypiName,
                           @Nullable String mavenGroupId, @Nullable String mavenArtifactId) {}
public record GeneratorContext(String outputDir, String version, PackageNames names,
                               List<String> sdkLanguages, Optional<DocsAiAssistant> ai) {}   // outputDir default ".emitkit/"
public interface Generator { GeneratorId id(); List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx); }
public record DocsExamples(String curl, String typescript, String python, String java, String notes) {}
public interface DocsAiAssistant { Map<String, DocsExamples> examples(Tag tag, List<Operation> ops); }  // key operationId; missing → template
public final class Templates {
  public static Templates forGenerator(String name);         // loads classpath:templates/{name}/
  public String render(String template, Object context);     // JMustache, escapeHTML(false), strictSections(true), "\n" line endings
}
// test fixtures
public final class GoldenFiles {
  public static void assertMatches(String fixtureName, List<GeneratedFile> files);   // compares with src/test/resources/golden/{fixture}/{path}
}
```
- `-Dgolden.update=true` (Gradle passes it through as a system property) rewrites the golden dir instead of asserting; the dir is deleted first so removed files disappear.
- A mismatch fails with a unified diff of the first differing file and the list of missing/extra paths.
- `ai` depends on `generators/core` for `DocsAiAssistant`/`DocsExamples`; `generators/core` never depends on `ai`.

## Tasks

### Task 1: Templates

- [ ] **Step 1: Write the failing test**

```java
@Test void rendersWithoutHtmlEscaping() { assertThat(Templates.forGenerator("dummy").render("hello", Map.of("name", "<A&B>"))).isEqualTo("Hello <A&B>\n"); }
@Test void missingVariableFails() { assertThatThrownBy(() -> t.render("hello", Map.of())).isInstanceOf(MustacheException.class); }
@Test void missingTemplateFails() { assertThatThrownBy(() -> t.render("nope", Map.of())).hasMessageContaining("templates/dummy/nope.mustache"); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:core:test --tests '*TemplatesTest'`

- [ ] **Step 3: Implement** — `Templates` with a cached compiler and template map.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:core:test --tests '*TemplatesTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): generator SPI and template engine"`

### Task 2: Golden harness

- [ ] **Step 1: Write the failing test**

```java
@Test void matchingOutputPasses(@TempDir Path golden) { write(golden, "a.txt", "x"); GoldenFiles.assertMatches(golden, List.of(file("a.txt", "x"))); }
@Test void differentContentFailsWithDiff(@TempDir Path golden) { write(golden, "a.txt", "x"); assertThatThrownBy(() -> GoldenFiles.assertMatches(golden, List.of(file("a.txt","y")))).hasMessageContaining("-x").hasMessageContaining("+y"); }
@Test void extraAndMissingFilesReported(@TempDir Path golden) { }
@Test void updateModeRewrites(@TempDir Path golden) { /* System.setProperty("golden.update","true") → files written, stale file removed */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:core:test --tests '*GoldenFilesTest'`

- [ ] **Step 3: Implement** — `GoldenFiles` (overload taking a `Path` for tests; public one resolves `src/test/resources/golden/{fixture}`); diff via `java-diff-utils`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:core:test --tests '*GoldenFilesTest'`

- [ ] **Step 5: Commit** — `git commit -m "test(gen): golden file harness"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

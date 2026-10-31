# GEN-11 · Workflows generator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate the GitHub Actions publish workflows for each selected SDK language, triggered on sdk/v* tags.

**Lane:** GEN · **Depends on:** [GEN-01](GEN-01-generator-spi.md) · **Size budget:** ~300 changed lines (hard limit 1000) · **Branch:** `ticket/gen-11`

**Spec:** v1 §6 Workflows row

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create in `backend/generators/workflows/`: `WorkflowsGenerator.java`, templates `publish-npm.yml.mustache`, `publish-pypi.yml.mustache`, `publish-maven.yml.mustache`
- Test: `backend/generators/workflows/src/test/java/.../WorkflowsGeneratorTest.java`

## Interfaces

- **Consumes:** GEN-01.
- **Produces:**
- `WorkflowsGenerator` (`WORKFLOWS`) emits to `.github/workflows/` (repo root, **not** under `outputDir`): `emitkit-publish-npm.yml` (typescript), `emitkit-publish-pypi.yml` (python), `emitkit-publish-maven.yml` (java) — only for languages in `ctx.sdkLanguages()`.
- `on: push: tags: ['sdk/v*']`; working directory `{outputDir}sdk/{lang}`; secrets `NPM_TOKEN`, `PYPI_TOKEN`, `MAVEN_USERNAME`/`MAVEN_PASSWORD`/`MAVEN_GPG_KEY`; Actions pinned by major version.

## Tasks

### Task 1: Workflows per language

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"typescript", "python", "java", "typescript,python,java"})
void matchesGolden(String langs) { GoldenFiles.assertMatches("langs-" + langs.replace(',', '-'), gen(List.of(langs.split(",")))); }
@Test void onlySelectedLanguages() { assertThat(gen(List.of("python"))).extracting(GeneratedFile::path).containsExactly(".github/workflows/emitkit-publish-pypi.yml"); }
@Test void yamlParsesAndTriggersOnSdkTags() {
  for (var f : gen(List.of("typescript","python","java"))) {
    Map<String, Object> y = new Yaml().load(f.content());
    assertThat(y).extractingByKey("on").asString().contains("sdk/v*");
  }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:workflows:test`

- [ ] **Step 3: Implement** — templates + generator; model is unused except `info.title` in the workflow name.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:workflows:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): publish workflows generator"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-11`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

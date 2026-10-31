# GEN-04 · Python SDK: client, errors, pyproject

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Python SDK: client, errors, pyproject: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/gen-04`

**Spec:** v1 §6 table row for this output (dir `{out}/sdk/python/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/python-sdk/`: `src/main/java/dev/emitkit/gen/pythonsdk/` + `src/main/resources/templates/python-sdk/`
- Templates: `client.py.mustache`, `errors.py.mustache`, `__init__.py.mustache`, `pyproject.toml.mustache`, `README.md.mustache`, `custom__init__.py.mustache`
- Test: `backend/generators/python-sdk/src/test/java/.../PythonSdkGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `PythonSdkGenerator` (`PY_SDK`), package dir `{pkg}` = `NameStrategy.forLanguage(PYTHON).file(pypiName)` with `-` → `_`.
- `{pkg}/client.py`: `class {Api}Client` (httpx `Client`) and `class Async{Api}Client` (httpx `AsyncClient`); same auth/retry options as the TS client (`api_key`, `bearer_token`, `basic`), `base_url`, `max_retries=2`; context-manager support.
- `{pkg}/errors.py`: `ApiError` and the same subclasses as TS (`NotFoundError`, …).
- `pyproject.toml`: `[project] name = pypiName`, `version = ctx.version()`, deps `httpx>=0.27`, `pydantic>=2.6`; `requires-python = ">=3.9"`; hatchling build backend.
- `{pkg}/custom/__init__.py` is `CUSTOM_STUB`.

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
@Test void idIsPY_SDK() { assertThat(generator.id()).isEqualTo(GeneratorId.PY_SDK); }
@Test void packageNameFromPypiName() { /* pypiName "acme-pets" → paths start with "sdk/python/acme_pets/" */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:python-sdk:test`

- [ ] **Step 3: Implement** — `PyClientView` view-model; models/resources added by GEN-05. First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:python-sdk:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): python sdk: client, errors, pyproject"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

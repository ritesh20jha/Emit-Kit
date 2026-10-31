# GEN-02 · TS SDK: client, errors, package

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TS SDK: client, errors, package: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/gen-02`

**Spec:** v1 §6 table row for this output (dir `{out}/sdk/typescript/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/typescript-sdk/`: `src/main/java/dev/emitkit/gen/typescriptsdk/` + `src/main/resources/templates/typescript-sdk/`
- Templates: `client.ts.mustache`, `errors.ts.mustache`, `index.ts.mustache`, `package.json.mustache`, `tsconfig.json.mustache`, `README.md.mustache`, `custom-index.ts.mustache`
- Test: `backend/generators/typescript-sdk/src/test/java/.../TypescriptSdkGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `TypeScriptSdkGenerator implements Generator` (`TS_SDK`), files under `{outputDir}sdk/typescript/`.
- `src/client.ts`: `export class {ApiName}Client` with `constructor(opts: { baseUrl?: string; fetch?: typeof fetch; maxRetries?: number } & AuthOptions)`; `AuthOptions` has one optional field per security scheme (`apiKey`, `bearerToken`, `basic: {username, password}`); retries on 429/5xx with exponential backoff (default 2 retries); default `baseUrl` = first server URL.
- `src/errors.ts`: `ApiError` (status, body, headers), `BadRequestError` 400, `UnauthorizedError` 401, `ForbiddenError` 403, `NotFoundError` 404, `ConflictError` 409, `RateLimitError` 429, `ServerError` 5xx.
- `package.json`: name `@{npmScope}/{npmName}` (no scope → `{npmName}`), version `ctx.version()`, `"type": "module"`, `exports` → `dist/index.js`.
- `src/custom/index.ts` has `FileKind.CUSTOM_STUB`; everything else `GENERATED`.
- `{ApiName}` = `NameStrategy.forLanguage(TYPESCRIPT).type(info.title)` + `Client` suffix only once.

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
@Test void idIsTS_SDK() { assertThat(generator.id()).isEqualTo(GeneratorId.TS_SDK); }
@Test void customStubIsMarked() { assertThat(files("petstore")).filteredOn(f -> f.path().endsWith("src/custom/index.ts")).singleElement().extracting(GeneratedFile::kind).isEqualTo(FileKind.CUSTOM_STUB); }
@Test void unscopedPackageName() { /* npmScope null → "name": "petstore-sdk" */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:typescript-sdk:test`

- [ ] **Step 3: Implement** — build one view-model (`TsClientView`) from `ApiModel` + context and render each template with it; resources/types files are added by GEN-03 (the generator calls an empty `List.of()` hook for now). First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:typescript-sdk:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): ts sdk: client, errors, package"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

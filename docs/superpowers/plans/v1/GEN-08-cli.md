# GEN-08 · CLI generator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CLI generator: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/gen-08`

**Spec:** v1 §6 table row for this output (dir `{out}/cli/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/cli/`: `src/main/java/dev/emitkit/gen/cli/` + `src/main/resources/templates/cli/`
- Templates: `index.ts.mustache`, `command.ts.mustache`, `package.json.mustache`, `tsconfig.json.mustache`, `README.md.mustache`
- Test: `backend/generators/cli/src/test/java/.../CliGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `CliGenerator` (`CLI`): Commander `^11` TS CLI. `src/index.ts` registers one command group per tag and one subcommand per operation: `{bin} {kebab(tag)} {kebab(operationId)}`.
- Flags: one `--{kebab(param)} <value>` per param (`requiredOption` when required); `--json <body|@file>` for the request body; global `--base-url`, `--token`, `--api-key`, `--output json|table` (default json).
- `package.json`: `"bin": {"{npmName}": "dist/index.js"}`, deps `commander@^11`; the CLI calls the API with `fetch` directly (it does not depend on the SDK package, so it works without it).

## Tasks

### Task 1: Golden output

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"petstore", "multi-tag", "reserved-words"})
void matchesGolden(String fixture) {
  GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
}
@Test void outputIsByteStable() {
  var m = FixtureSpecs.load("petstore");
  assertThat(generator.generate(m, ctx())).isEqualTo(generator.generate(m, ctx()));
}
@Test void idIsCLI() { assertThat(generator.id()).isEqualTo(GeneratorId.CLI); }
@Test void requiredParamIsRequiredOption() { assertThat(content("petstore", "src/commands/pets.ts")).contains(".requiredOption(\"--pet-id <value>\""); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:cli:test`

- [ ] **Step 3: Implement** — one `commands/{tag}.ts` per tag. First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:cli:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): cli generator"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-08`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

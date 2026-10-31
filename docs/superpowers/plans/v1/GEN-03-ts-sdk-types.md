# GEN-03 · TS SDK: types + resources

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TS SDK: types + resources: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-02](GEN-02-ts-sdk-client.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/gen-03`

**Spec:** v1 §6 table row for this output (dir `{out}/sdk/typescript/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/typescript-sdk/`: `src/main/java/dev/emitkit/gen/typescriptsdk/` + `src/main/resources/templates/typescript-sdk/`
- Templates: `types.ts.mustache`, `resource.ts.mustache`
- Create: `TsTypeRenderer.java` (Schema → TS type string)
- Test: `backend/generators/typescript-sdk/src/test/java/.../TypescriptSdkGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `src/types.ts`: one `export interface` per object schema, `export type` for enums (string-literal union), oneOf (union), allOf (intersection), arrays; nullable → `| null`; optional props `?:`; open object → `Record<string, unknown>`; map → `Record<string, T>`.
- `src/resources/{tag}.ts` (file name `NameStrategy.file(tag)`; colliding tags `user`/`User` merge into one file and one class — documented in a test): `export class {Tag}Resource` with one `async {member(operationId)}(params, body?)` per operation; path params interpolated with `encodeURIComponent`; query params skip `undefined`.
- Client exposes one property per resource: `readonly {member(tag)}: {Tag}Resource`.

## Tasks

### Task 1: Golden output

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"petstore", "auth-schemes", "polymorphism", "enums", "pagination", "no-operation-ids", "reserved-words", "deep-nesting", "multi-tag"})
void matchesGolden(String fixture) {
  GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
}
@Test void outputIsByteStable() {
  var m = FixtureSpecs.load("petstore");
  assertThat(generator.generate(m, ctx())).isEqualTo(generator.generate(m, ctx()));
}
@Test void idIsTS_SDK() { assertThat(generator.id()).isEqualTo(GeneratorId.TS_SDK); }
@Test void reservedOperationIdIsEscaped() { assertThat(content("reserved-words", "src/resources/default.ts")).contains("async delete_("); }
@Test void collidingTagsMergeIntoOneResource() { assertThat(paths("reserved-words")).filteredOn(p -> p.contains("resources/user")).hasSize(1); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:typescript-sdk:test`

- [ ] **Step 3: Implement** — `TsTypeRenderer` is a `switch` over the sealed `Schema`; inline object schemas inside properties are rendered inline (no generated names). First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:typescript-sdk:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): ts sdk: types + resources"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

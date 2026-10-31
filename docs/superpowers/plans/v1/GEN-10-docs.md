# GEN-10 · Docs generator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Docs generator: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/gen-10`

**Spec:** v1 §6 table row for this output (dir `{out}/docs/v{version}/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/docs/`: `src/main/java/dev/emitkit/gen/docs/` + `src/main/resources/templates/docs/`
- Templates: `index.md.mustache`, `tag.md.mustache`, `examples/curl.mustache`, `examples/typescript.mustache`, `examples/python.mustache`, `examples/java.mustache`
- Test: `backend/generators/docs/src/test/java/.../DocsGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `DocsGenerator` (`DOCS`): `index.md` (title, version, servers, auth, table of tags) and `{file(tag)}.md` per tag; each operation has method, path, description, params table (`Name | In | Type | Required | Description`), request/response schema tables, and examples (curl, TS, Python, Java).
- If `ctx.ai()` is present: call `examples(tag, ops)` **once per tag**; for each operation use the AI `DocsExamples` if its key is present, else the template example. `notes`, when non-blank, is added under the examples as "Notes".
- AI exceptions are already swallowed by the assistant; the generator treats any thrown exception the same as an empty map (defensive).

## Tasks

### Task 1: Golden output

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"petstore", "auth-schemes", "multi-tag"})
void matchesGolden(String fixture) {
  GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
}
@Test void outputIsByteStable() {
  var m = FixtureSpecs.load("petstore");
  assertThat(generator.generate(m, ctx())).isEqualTo(generator.generate(m, ctx()));
}
@Test void idIsDOCS() { assertThat(generator.id()).isEqualTo(GeneratorId.DOCS); }
@Test void aiExamplesUsedWhenPresent_templateOtherwise() {
  var ai = new StubAi(Map.of("listPets", new DocsExamples("curl AI", "ts AI", "py AI", "java AI", "AI note")));
  var md = content(generator.generate(FixtureSpecs.load("petstore"), ctx(ai)), "pets.md");
  assertThat(md).contains("curl AI").contains("AI note");
  assertThat(section(md, "getPet")).doesNotContain("AI").contains("curl -X GET");
}
@Test void aiCalledOncePerTag() { /* multi-tag (3 tags) → stub.calls()==3 */ }
@Test void aiThrowing_fallsBackToTemplate() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:docs:test`

- [ ] **Step 3: Implement** — `DocsView` per tag; markdown tables escape `|`. First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:docs:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): docs generator"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-10`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

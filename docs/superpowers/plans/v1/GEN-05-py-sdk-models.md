# GEN-05 · Python SDK: Pydantic models + resources

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Python SDK: Pydantic models + resources: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-04](GEN-04-py-sdk-client.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/gen-05`

**Spec:** v1 §6 table row for this output (dir `{out}/sdk/python/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/python-sdk/`: `src/main/java/dev/emitkit/gen/pythonsdk/` + `src/main/resources/templates/python-sdk/`
- Templates: `models.py.mustache`, `resource.py.mustache`
- Create: `PyTypeRenderer.java`
- Test: `backend/generators/python-sdk/src/test/java/.../PythonSdkGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `{pkg}/models.py`: Pydantic v2 `BaseModel` per object schema; field names snake_case with `Field(alias="originalName")` when they differ, `model_config = ConfigDict(populate_by_name=True)`; enums as `class X(str, Enum)`; oneOf → `Annotated[Union[...], Field(discriminator=...)]` when a discriminator exists; nullable → `Optional[T]`; open object → `dict[str, Any]`; forward refs resolved with `model_rebuild()` at file end (recursion).
- `{pkg}/resources/{tag}.py`: sync `{Tag}Resource` and `Async{Tag}Resource`, one method per operation (`member()` names), keyword-only params.

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
@Test void idIsPY_SDK() { assertThat(generator.id()).isEqualTo(GeneratorId.PY_SDK); }
@Test void reservedPropertyGetsAlias() { assertThat(content("reserved-words", "models.py")).contains("from_: ").contains("alias=\"from\""); }
@Test void recursiveModelRebuilt() { assertThat(content("deep-nesting", "models.py")).contains("Node.model_rebuild()"); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:python-sdk:test`

- [ ] **Step 3: Implement** — `PyTypeRenderer` over the sealed `Schema`; models are emitted in dependency order with forward-ref strings. First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:python-sdk:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): python sdk: pydantic models + resources"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

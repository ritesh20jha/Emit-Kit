# GEN-07 · Java SDK: models (records) + resources

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java SDK: models (records) + resources: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-06](GEN-06-java-sdk-client.md) · **Size budget:** ~750 changed lines (hard limit 1000) · **Branch:** `ticket/gen-07`

**Spec:** v1 §6 table row for this output (dir `{out}/sdk/java/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/java-sdk/`: `src/main/java/dev/emitkit/gen/javasdk/` + `src/main/resources/templates/java-sdk/`
- Templates: `Model.java.mustache` (record), `Enum.java.mustache`, `OneOf.java.mustache` (sealed interface), `Resource.java.mustache`
- Create: `JavaTypeRenderer.java`
- Test: `backend/generators/java-sdk/src/test/java/.../JavaSdkGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `model/{Type}.java`: one `record` per object schema with `@JsonProperty("original")` on every component; enums with `@JsonValue`; oneOf → `sealed interface` + `@JsonTypeInfo(use = NAME, property = discriminator)` / `@JsonSubTypes` when a discriminator exists, else `JsonNode`; allOf → record with the merged properties; nullable/optional → boxed types (no `Optional` fields); open object → `Map<String, Object>`; inline object properties get a nested record named `{Parent}{Prop}`.
- `resources/{Tag}Api.java`: one method per operation, required params first, then an `{Op}Options` record for optional query params when there are any.
- Client exposes `{member(tag)}()` returning the resource.

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
@Test void idIsJAVA_SDK() { assertThat(generator.id()).isEqualTo(GeneratorId.JAVA_SDK); }
@Test void schemaNamedObjectIsRenamed() { assertThat(paths("reserved-words")).contains("model/Object_.java"); }
@Test void inlineObjectGetsNestedRecord() { assertThat(content("deep-nesting", "model/Order.java")).contains("record OrderShipping("); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:java-sdk:test`

- [ ] **Step 3: Implement** — `JavaTypeRenderer` over the sealed `Schema`. First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:java-sdk:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): java sdk: models (records) + resources"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-07`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

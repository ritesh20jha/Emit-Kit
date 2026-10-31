# GEN-00 · Shared fixture specs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One shared set of fixture specs that every generator's golden tests and the compile tests use, including the hard naming cases.

**Lane:** GEN · **Depends on:** [SPEC-03](SPEC-03-parser-schemas.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/gen-00`

**Spec:** v1 §6, ov §6 (~10 fixture specs); README Review Focus #2

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/generators/core/src/testFixtures/resources/specs/` — `petstore.yaml`, `auth-schemes.yaml`, `polymorphism.yaml`, `enums.yaml`, `pagination.yaml`, `no-operation-ids.yaml`, `reserved-words.yaml`, `deep-nesting.yaml`, `multi-tag.yaml`
- Create: `backend/generators/core/src/testFixtures/java/dev/emitkit/gen/core/fixtures/FixtureSpecs.java`
- Create: `scripts/gen-big-spec.py` → writes `big-200-ops.yaml` (200 operations, 20 tags, 40 schemas) into the same folder; it is written to `src/testFixtures/resources/golden-inputs/big-200-ops.yaml` (excluded from the PR size count by T02); `FixtureSpecs.load` looks in both folders
- Test: `backend/generators/core/src/test/java/dev/emitkit/gen/core/fixtures/FixtureSpecsTest.java`

## Interfaces

- **Produces:**
```java
public final class FixtureSpecs {
  public static final List<String> ALL = List.of("petstore","auth-schemes","polymorphism","enums","pagination",
      "no-operation-ids","reserved-words","deep-nesting","multi-tag","big-200-ops");
  public static ApiModel load(String name);
}
```
**What each fixture must contain:**
- `auth-schemes`: apiKey in header, apiKey in query, http bearer, http basic, oauth2; per-operation security overrides.
- `polymorphism`: oneOf with discriminator, oneOf without, allOf inheritance, nullable ref.
- `reserved-words`: operationIds `class`, `delete`, `import`, `default`; tags `user` and `User`; schemas `Error`, `Object`, `String`, `None`; properties `type`, `from`, `self`, `2fa`; a path param named `in`.
- `pagination`: cursor + limit query params, `Link` header response.
- `deep-nesting`: 5 levels of inline objects, arrays of arrays, recursive `Node`.

## Tasks

### Task 1: Fixtures load cleanly

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @FieldSource("dev.emitkit.gen.core.fixtures.FixtureSpecs#ALL")
void parsesWithoutErrors(String name) {
  var r = new SpecParser().parse(FixtureSpecs.raw(name));
  assertThat(r.errors()).isEmpty();
}
@Test void big200HasTwoHundredOps() { assertThat(FixtureSpecs.load("big-200-ops").operations()).hasSize(200); }
@Test void reservedWordsFixtureHasCollidingTags() { assertThat(FixtureSpecs.load("reserved-words").tags()).extracting(Tag::name).contains("user", "User"); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:core:test --tests '*FixtureSpecsTest'`

- [ ] **Step 3: Implement** — write the YAML fixtures and the generator script (seeded, deterministic).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:core:test --tests '*FixtureSpecsTest'`

- [ ] **Step 5: Commit** — `git commit -m "test(gen): shared fixture specs"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-00`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

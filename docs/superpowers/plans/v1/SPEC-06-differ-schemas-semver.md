# SPEC-06 · Differ: schema changes + SemVer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add schema-level breaking-change rules and the version calculator.

**Lane:** SPEC · **Depends on:** [SPEC-05](SPEC-05-differ-operations.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/spec-06`

**Spec:** v1 §5.4 (schema rules), §5.5

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/spec-engine/src/main/java/dev/emitkit/spec/diff/DefaultSchemaDiffer.java`
- Create: `backend/spec-engine/src/main/java/dev/emitkit/spec/version/VersionCalculator.java`, `VersionResult.java`, `VersionStrategy.java`, `ChangeType.java`
- Modify: `backend/spec-engine/build.gradle.kts` (`com.github.zafarkhaja:java-semver`)
- Test: `backend/spec-engine/src/test/java/dev/emitkit/spec/diff/SchemaDiffTest.java`, `backend/spec-engine/src/test/java/dev/emitkit/spec/version/VersionCalculatorTest.java`

## Interfaces

- **Produces:**
```java
public enum VersionStrategy { EMITKIT_MANAGED, SPEC_VERSION }   // same names as domain enum; domain maps by name
public enum ChangeType { MAJOR, MINOR, PATCH }
public record VersionResult(String version, Optional<ChangeType> changeType) {}   // empty = unchanged
public final class VersionCalculator {
  public VersionResult next(VersionStrategy strategy, Optional<String> lastVersion, SpecDiff diff, String infoVersion);
  // lastVersion empty = first run
}
```
- Schema compare follows refs through both models' `schemas` maps with a visited set (no infinite loop on recursion). Request side: required property added → breaking; optional added → not. Response side: property removed → breaking; added → not. Both sides: enum value removed → breaking, added → not; type changed (different Schema kind or PrimitiveType) → breaking.
- `ChangeType` reported is the one actually applied to the number: below 1.0.0, breaking → `MINOR`, additions/modifications → `PATCH`.

## Tasks

### Task 1: Schema rules

- [ ] **Step 1: Write the failing test**

```java
@Test void requiredRequestPropertyAdded_isBreaking() { /* createPet body Pet gains required "tag" → REQUEST_REQUIRED_PROPERTY_ADDED, "body.tag", true */ }
@Test void optionalRequestPropertyAdded_notBreaking() { }
@Test void responsePropertyRemoved_isBreaking() { /* getPet 200 Pet loses "name" → RESPONSE_PROPERTY_REMOVED, "200.name", true */ }
@Test void responsePropertyAdded_notBreaking() { }
@Test void enumValueRemoved_isBreaking() { }
@Test void typeChanged_isBreaking() { /* id integer → string: TYPE_CHANGED */ }
@Test void changeInsideRefIsFound() { /* Owner.address.zip removed in response via $ref chain → "200.owner.address.zip" */ }
@Test void recursiveSchemaTerminates() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*SchemaDiffTest'`

- [ ] **Step 3: Implement** — `DefaultSchemaDiffer` wired into `SpecDiffer` as the default.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*SchemaDiffTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): schema-level breaking change rules"`

### Task 2: Version calculator

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest(name = "{0} + {1} → {2}") @CsvSource(nullValues = "-", value = {
  // last,  diff,      expected, changeType
  "-,       added,     0.1.0,    MINOR",
  "0.4.2,   breaking,  0.5.0,    MINOR",
  "0.4.2,   removed,   0.5.0,    MINOR",
  "0.4.2,   added,     0.4.3,    PATCH",
  "0.4.2,   modified,  0.4.3,    PATCH",
  "1.2.3,   breaking,  2.0.0,    MAJOR",
  "1.2.3,   added,     1.3.0,    MINOR",
  "1.2.3,   none,      1.2.3,    -"})
void emitkitManaged(String last, String diffKind, String expected, ChangeType type) { ... }

@Test void specVersion_usesInfoVersion() { assertThat(calc.next(SPEC_VERSION, Optional.of("1.0.0"), anyDiff, "3.4.5").version()).isEqualTo("3.4.5"); }
@Test void specVersion_invalidSemver_defaults() { assertThat(calc.next(SPEC_VERSION, Optional.empty(), anyDiff, "abc").version()).isEqualTo("0.1.0"); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*VersionCalculatorTest'`

- [ ] **Step 3: Implement** — first run (empty `lastVersion`) is always `0.1.0`; `SPEC_VERSION` reports changeType from comparing parsed versions (major/minor/patch bump), empty if equal.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*VersionCalculatorTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): semver calculator"`

---

## Done when

- [ ] All tasks above are committed on `ticket/spec-06`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

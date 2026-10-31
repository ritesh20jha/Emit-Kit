# SPEC-05 · Differ: operations & parameters

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compare two ApiModels by operationId and classify operation and parameter changes as breaking or not.

**Lane:** SPEC · **Depends on:** [SPEC-03](SPEC-03-parser-schemas.md) · **Size budget:** ~650 changed lines (hard limit 1000) · **Branch:** `ticket/spec-05`

**Spec:** v1 §5.4

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/spec-engine/src/main/java/dev/emitkit/spec/diff/SpecDiffer.java`, `SpecDiff.java`, `OpRef.java`, `OpChange.java`, `Change.java`, `ChangeKind.java`, `OperationDiffer.java`
- Test: `backend/spec-engine/src/test/java/dev/emitkit/spec/diff/OperationDiffTest.java` (builds models in code with a small `ModelBuilder` test helper)

## Interfaces

- **Produces:**
```java
public record OpRef(String operationId, String method, String path) {}
public enum ChangeKind { PARAM_ADDED, PARAM_REMOVED, PARAM_BECAME_REQUIRED, PARAM_BECAME_OPTIONAL, BODY_BECAME_REQUIRED,
  REQUEST_REQUIRED_PROPERTY_ADDED, REQUEST_PROPERTY_ADDED, RESPONSE_PROPERTY_REMOVED, RESPONSE_PROPERTY_ADDED,
  ENUM_VALUE_REMOVED, ENUM_VALUE_ADDED, TYPE_CHANGED, DESCRIPTION_CHANGED }
public record Change(ChangeKind kind, String location, boolean breaking) {}   // location e.g. "query.limit", "body.owner.name", "200.items[].id"
public record OpChange(OpRef op, List<Change> changes) {}
public record SpecDiff(List<OpRef> added, List<OpRef> removed, List<OpChange> modified) {
  public boolean hasBreaking();      // removed non-empty or any change breaking
  public boolean isEmpty();
  public static SpecDiff empty();
}
public final class SpecDiffer { public SpecDiff diff(@Nullable ApiModel before, ApiModel after); }  // before null → everything added
```
Ordering: `added`/`removed` follow the order of the model they come from; `modified` follows `after`. Operations with zero changes are not listed. JSON shape (Jackson default record mapping) is what's stored in `generation_runs.diff_summary` and returned by the API.

## Tasks

### Task 1: Operation and parameter rules

- [ ] **Step 1: Write the failing test**

```java
@Test void operationRemoved_isBreaking() { var d = diff(model(listPets, getPet), model(listPets)); assertThat(d.removed()).extracting(OpRef::operationId).containsExactly("getPet"); assertThat(d.hasBreaking()).isTrue(); }
@Test void operationAdded_notBreaking() { var d = diff(model(listPets), model(listPets, getPet)); assertThat(d.added()).hasSize(1); assertThat(d.hasBreaking()).isFalse(); }
@Test void newRequiredParam_isBreaking() { assertThat(onlyChange(listPets, listPets.withParam(query("limit", true)))).isEqualTo(new Change(PARAM_ADDED, "query.limit", true)); }
@Test void newOptionalParam_notBreaking() { assertThat(onlyChange(listPets, listPets.withParam(query("limit", false))).breaking()).isFalse(); }
@Test void optionalBecomesRequired_isBreaking() { /* PARAM_BECAME_REQUIRED, true */ }
@Test void requiredBecomesOptional_notBreaking() { /* PARAM_BECAME_OPTIONAL, false */ }
@Test void paramRemoved_isBreaking() { }
@Test void bodyBecomesRequired_isBreaking() { /* BODY_BECAME_REQUIRED, location "body" */ }
@Test void descriptionChanged_notBreaking() { }
@Test void nullBefore_allAdded() { assertThat(differ.diff(null, model(listPets, getPet)).added()).hasSize(2); }
@Test void identicalModels_emptyDiff() { assertThat(diff(m, m).isEmpty()).isTrue(); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*OperationDiffTest'`

- [ ] **Step 3: Implement** — `SpecDiffer` matches by `operationId`; `OperationDiffer` compares params keyed by `(in, name)`; schema-level comparisons are delegated to a `SchemaDiffer` interface that SPEC-06 implements (stub returns no changes here).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*OperationDiffTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): operation and parameter diff"`

---

## Done when

- [ ] All tasks above are committed on `ticket/spec-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

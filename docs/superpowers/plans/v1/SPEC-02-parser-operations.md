# SPEC-02 · Parser: operations & parameters

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse OpenAPI 2.0/3.0/3.1 (YAML or JSON) into ApiModel operations, parameters, bodies, responses, security, servers and tags.

**Lane:** SPEC · **Depends on:** [SPEC-01](SPEC-01-api-model.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/spec-02`

**Spec:** v1 §5.1 step 2, §5.2

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/spec-engine/src/main/java/dev/emitkit/spec/parse/SpecParser.java`, `ParseResult.java`, `OperationMapper.java`, `SchemaMapper.java` (stub returning `PrimitiveSchema(STRING)` for now; SPEC-03 fills it)
- Create: `backend/spec-engine/src/test/resources/specs/petstore-2.0.yaml`, `petstore-3.0.json`, `petstore-3.1.yaml`, `no-ids.yaml`, `path-level-params.yaml`, `invalid.yaml`
- Test: `backend/spec-engine/src/test/java/dev/emitkit/spec/parse/SpecParserOperationsTest.java`
- Modify: `backend/spec-engine/build.gradle.kts` (`io.swagger.parser.v3:swagger-parser` — keeps its own Jackson 2; nothing in the public API exposes swagger types)

## Interfaces

- **Produces:**
```java
public record ParseResult(@Nullable ApiModel model, List<String> errors, @Nullable String infoVersion, String contentHash, String format) {
  public boolean ok() { return model != null && errors.isEmpty(); } }   // format "yaml" | "json"
public final class SpecParser {
  public ParseResult parse(String content);   // never throws for bad input
}
```
- `contentHash` = lowercase hex SHA-256 of the UTF-8 content.
- Operation order: paths in document order, methods in order `get, put, post, delete, options, head, patch, trace`.
- `tag` = first tag, or `"default"` when none.
- Synthesized id: `{method}{PathInPascalCase}` with `{param}` → `By{Param}`, e.g. `GET /users/{id}` → `getUsersById`; sets `operationIdSynthesized=true`.
- Swagger 2.0 is converted with `OpenAPIV3Parser`'s v2 converter (`ParseOptions.setResolve(true)`, `setResolveFully(false)`).

## Tasks

### Task 1: Operations across versions

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"petstore-2.0.yaml", "petstore-3.0.json", "petstore-3.1.yaml"})
void allVersionsGiveSameOperations(String file) {
  var r = parser.parse(fixture(file));
  assertThat(r.ok()).isTrue();
  assertThat(r.model().operations()).extracting(Operation::operationId, Operation::method, Operation::path)
      .containsExactly(tuple("listPets","GET","/pets"), tuple("createPet","POST","/pets"), tuple("getPet","GET","/pets/{petId}"));
}
@Test void formatDetected() { assertThat(parser.parse(fixture("petstore-3.0.json")).format()).isEqualTo("json"); }
@Test void contentHashIsSha256Hex() { assertThat(parser.parse("openapi: 3.1.0\n...").contentHash()).hasSize(64); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*SpecParserOperationsTest'`

- [ ] **Step 3: Implement** — `SpecParser` + `OperationMapper` (servers, tags, securitySchemes, security names, responses, request body with first content type preferring `application/json`).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*SpecParserOperationsTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): parse operations across openapi versions"`

### Task 2: Parameter merge, synthesized ids, errors

- [ ] **Step 1: Write the failing test**

```java
@Test void operationLevelParamWinsOnNameAndIn() {
  var op = only(parser.parse(fixture("path-level-params.yaml")));   // path: limit(query, optional); op: limit(query, required) + id(path)
  assertThat(op.parameters()).extracting(Parameter::name, Parameter::required).containsExactly(tuple("id", true), tuple("limit", true));
}
@Test void missingOperationId_isSynthesized() {
  var ops = parser.parse(fixture("no-ids.yaml")).model().operations();
  assertThat(ops).extracting(Operation::operationId).contains("getUsersById", "postUsers");
  assertThat(ops).allMatch(Operation::operationIdSynthesized);
}
@Test void invalidSpec_returnsErrorsWithoutThrowing() {
  var r = parser.parse(fixture("invalid.yaml"));
  assertThat(r.ok()).isFalse(); assertThat(r.errors()).isNotEmpty();
}
@Test void notYamlAtAll_returnsErrors() { assertThat(parser.parse("{{{").errors()).isNotEmpty(); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*SpecParserOperationsTest'`

- [ ] **Step 3: Implement** — merge with a `LinkedHashMap` keyed by `name + "|" + in`, path-level first then op-level overwrite; params sorted path params first (in template order), then the rest in document order.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*SpecParserOperationsTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): param merge and synthesized operation ids"`

---

## Done when

- [ ] All tasks above are committed on `ticket/spec-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

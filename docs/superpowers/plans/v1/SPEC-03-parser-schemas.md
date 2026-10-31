# SPEC-03 · Parser: schemas

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Map every OpenAPI schema form to the sealed Schema types, including refs, nullability, enums, composition and recursion.

**Lane:** SPEC · **Depends on:** [SPEC-02](SPEC-02-parser-operations.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/spec-03`

**Spec:** v1 §5.2

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/spec-engine/src/main/java/dev/emitkit/spec/parse/SchemaMapper.java` (replace stub)
- Create: `backend/spec-engine/src/test/resources/specs/schemas.yaml` (one component per case below)
- Test: `backend/spec-engine/src/test/java/dev/emitkit/spec/parse/SchemaMapperTest.java`

## Interfaces

- **Produces:**
- `SchemaMapper.map(io.swagger.v3.oas.models.media.Schema<?> s): Schema` (package-private).
- `$ref: '#/components/schemas/Pet'` → `RefSchema("Pet")`; never inlined. Component schemas go to `ApiModel.schemas` in document order.
- Nullable: 3.0 `nullable: true`; 3.1 `type: [X, "null"]`; `oneOf` with a `{type: null}` branch → the other branch with `nullable=true`.
- `additionalProperties: <schema>` → `ObjectSchema(..., additionalProperties = <mapped schema>)` (a map). `additionalProperties: true`, and a schema with no `type` and no other keywords, → an **open object** `ObjectSchema(Map.of(), Set.of(), null, false, null)`; generators render it as `unknown` (TS) / `Any` (Python) / `Object` (Java).

## Tasks

### Task 1: Schema kinds

- [ ] **Step 1: Write the failing test**

```java
@Test void primitiveWithFormat() { assertThat(map("DateField")).isEqualTo(new PrimitiveSchema(STRING, "date-time", false, null)); }
@Test void refKeptAsRef() { assertThat(prop("Owner", "pet")).isEqualTo(new RefSchema("Pet", false, null)); }
@Test void enumOfStrings() { assertThat(map("Status")).isEqualTo(new EnumSchema(STRING, List.of("available","sold"), false, null)); }
@Test void arrayOfRefs() { assertThat(map("PetList")).isEqualTo(new ArraySchema(new RefSchema("Pet", false, null), false, null)); }
@Test void requiredSetKept() { assertThat(((ObjectSchema) map("Pet")).required()).containsExactly("id", "name"); }
@Test void oneOfWithDiscriminator() { var s = (OneOfSchema) map("Shape"); assertThat(s.discriminator()).isEqualTo("kind"); assertThat(s.options()).hasSize(2); }
@Test void allOfParts() { assertThat(((AllOfSchema) map("Dog")).parts()).hasSize(2); }
@Test void nullable30() { assertThat(map("Nullable30").nullable()).isTrue(); }
@Test void nullable31TypeArray() { assertThat(map("Nullable31")).isEqualTo(new PrimitiveSchema(STRING, null, true, null)); }
@Test void oneOfWithNullBranch_becomesNullable() { assertThat(map("MaybePet")).isEqualTo(new RefSchema("Pet", true, null)); }
@Test void additionalPropertiesMap() { assertThat(((ObjectSchema) map("Labels")).additionalProperties()).isInstanceOf(PrimitiveSchema.class); }
@Test void recursiveSchemaTerminates() { /* Node.children: array of $ref Node → ArraySchema(RefSchema("Node")) */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*SchemaMapperTest'`

- [ ] **Step 3: Implement** — a `switch` over the swagger schema's resolved type; recursion is safe because refs are never followed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*SchemaMapperTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): map openapi schemas to ApiModel"`

### Task 2: ApiModel JSON round-trip

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"petstore-3.1.yaml", "schemas.yaml"})
void roundTrips(String file) {
  var m = parser.parse(fixture(file)).model();
  assertThat(ApiModelJson.fromJson(ApiModelJson.toJson(m))).isEqualTo(m);
}
@Test void schemaKindIsTagged() { assertThat(ApiModelJson.toJson(model)).contains("\"kind\":\"ref\""); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*ApiModelJsonTest'`

- [ ] **Step 3: Implement** — `public final class ApiModelJson { static String toJson(ApiModel); static ApiModel fromJson(String); }` in `dev.emitkit.spec.model`, using a private Jackson 3 `JsonMapper` with a mixin that adds `@JsonTypeInfo(use = NAME, property = "kind")` + `@JsonSubTypes` (`object, array, primitive, enum, ref, oneOf, allOf`) to `Schema`, so the model records stay annotation-free. Used for `spec_snapshots.api_model` (WRK-03) and the diff endpoint (PRJ-06).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*ApiModelJsonTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): ApiModel json serialization"`

---

## Done when

- [ ] All tasks above are committed on `ticket/spec-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

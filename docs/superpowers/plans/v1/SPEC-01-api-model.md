# SPEC-01 · ApiModel + naming

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define the immutable, language-neutral ApiModel every generator reads, and the per-language naming rules.

**Lane:** SPEC · **Depends on:** [T01](T01-backend-skeleton.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/spec-01`

**Spec:** v1 §5.2

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/spec-engine/src/main/java/dev/emitkit/spec/model/` — `ApiModel.java`, `Info.java`, `Server.java`, `SecurityScheme.java`, `Tag.java`, `Operation.java`, `Parameter.java`, `ParamLocation.java`, `RequestBody.java`, `Response.java`, `Schema.java` (+ the 7 permitted records)
- Create: `backend/spec-engine/src/main/java/dev/emitkit/spec/naming/NameStrategy.java`, `CamelCase.java`, `SnakeCase.java`, `PascalCase.java`, `ReservedWords.java`, `Language.java`
- Test: `backend/spec-engine/src/test/java/dev/emitkit/spec/naming/NameStrategyTest.java`, `backend/spec-engine/src/test/java/dev/emitkit/spec/model/ApiModelTest.java`

## Interfaces

- **Produces:**
```java
public record ApiModel(Info info, List<Server> servers, Map<String, SecurityScheme> securitySchemes,
                       List<Tag> tags, List<Operation> operations, Map<String, Schema> schemas) {}
public record Info(String title, String version, @Nullable String description, @Nullable String contactEmail) {}
public record Server(String url, @Nullable String description) {}
public record SecurityScheme(String name, Kind kind, @Nullable String in, @Nullable String paramName, @Nullable String scheme) {
  public enum Kind { API_KEY, HTTP, OAUTH2, OPENID_CONNECT } }
public record Tag(String name, @Nullable String description) {}
public record Operation(String operationId, boolean operationIdSynthesized, String method, String path, String tag,
                        @Nullable String summary, @Nullable String description, List<Parameter> parameters,
                        @Nullable RequestBody requestBody, Map<String, Response> responses,
                        List<String> security, boolean deprecated) {}
public record Parameter(String name, ParamLocation in, boolean required, Schema schema, @Nullable String description) {}
public enum ParamLocation { PATH, QUERY, HEADER, COOKIE }
public record RequestBody(boolean required, String contentType, Schema schema) {}
public record Response(String status, @Nullable String description, @Nullable Schema schema) {}
public sealed interface Schema permits ObjectSchema, ArraySchema, PrimitiveSchema, EnumSchema, RefSchema, OneOfSchema, AllOfSchema {
  boolean nullable(); @Nullable String description(); }
public record ObjectSchema(Map<String, Schema> properties, Set<String> required, @Nullable Schema additionalProperties, boolean nullable, String description) implements Schema {}
public record ArraySchema(Schema items, boolean nullable, String description) implements Schema {}
public record PrimitiveSchema(PrimitiveType type, @Nullable String format, boolean nullable, String description) implements Schema {}
public enum PrimitiveType { STRING, INTEGER, NUMBER, BOOLEAN }
public record EnumSchema(PrimitiveType type, List<String> values, boolean nullable, String description) implements Schema {}
public record RefSchema(String name, boolean nullable, String description) implements Schema {}   // name = key in ApiModel.schemas
public record OneOfSchema(List<Schema> options, @Nullable String discriminator, boolean nullable, String description) implements Schema {}
public record AllOfSchema(List<Schema> parts, boolean nullable, String description) implements Schema {}

public enum Language { TYPESCRIPT, PYTHON, JAVA }
public interface NameStrategy {
  String type(String raw);     // PascalCase type name, reserved-safe
  String member(String raw);   // method/field: camel (TS, Java) or snake (Python)
  String file(String raw);     // file stem: kebab (TS), snake (Python), Pascal (Java)
  boolean isReserved(String name);
  static NameStrategy forLanguage(Language l);
}
```
All lists/maps are copied with `List.copyOf` / unmodifiable `LinkedHashMap` copies in compact constructors (order preserved — generators rely on it for byte-stable output).

## Tasks

### Task 1: Model records

- [ ] **Step 1: Write the failing test**

```java
@Test void collectionsAreImmutableAndOrdered() {
  var props = new LinkedHashMap<String, Schema>(); props.put("b", str()); props.put("a", str());
  var o = new ObjectSchema(props, Set.of("a"), null, false, null);
  props.put("c", str());
  assertThat(o.properties().keySet()).containsExactly("b", "a");
  assertThatThrownBy(() -> o.properties().put("x", str())).isInstanceOf(UnsupportedOperationException.class);
}
@Test void schemaIsSealed() { assertThat(Schema.class.getPermittedSubclasses()).hasSize(7); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*ApiModelTest'`

- [ ] **Step 3: Implement** — the records above.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*ApiModelTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): ApiModel records"`

### Task 2: Naming strategies

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @CsvSource({
  // raw,          ts.member,   py.member,   java.member, ts.type,   py.file,    java.file
  "user-id,        userId,      user_id,     userId,      UserId,    user_id,    UserId",
  "HTTPServer,     httpServer,  http_server, httpServer,  HttpServer,http_server,HttpServer",
  "list_pets,      listPets,    list_pets,   listPets,    ListPets,  list_pets,  ListPets",
  "2fa,            _2fa,        _2fa,        _2fa,        _2fa,      _2fa,       _2fa"})
void converts(String raw, String ts, String py, String java, String tsType, String pyFile, String javaFile) { ... }

@Test void reservedWordsAreEscaped() {
  assertThat(NameStrategy.forLanguage(TYPESCRIPT).member("delete")).isEqualTo("delete_");
  assertThat(NameStrategy.forLanguage(PYTHON).member("class")).isEqualTo("class_");
  assertThat(NameStrategy.forLanguage(JAVA).member("class")).isEqualTo("class_");
  assertThat(NameStrategy.forLanguage(JAVA).type("Object")).isEqualTo("Object_");   // clashes with java.lang
  assertThat(NameStrategy.forLanguage(PYTHON).member("None")).isEqualTo("none");    // snake makes it safe
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :spec-engine:test --tests '*NameStrategyTest'`

- [ ] **Step 3: Implement** — split raw into words on `[-_ .]`, case changes and acronym boundaries; join per style; prefix `_` if it starts with a digit; append `_` if reserved. `ReservedWords` holds per-language sets: TS keywords + `Error`, `Object`, `Promise`; Python keywords + soft keywords + `self`; Java keywords + `Object`, `String`, `Class`, `Override`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :spec-engine:test --tests '*NameStrategyTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(spec): per-language naming strategies"`

---

## Done when

- [ ] All tasks above are committed on `ticket/spec-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

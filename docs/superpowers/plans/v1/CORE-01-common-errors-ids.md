# CORE-01 · Common: errors, IDs, problem-details types

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every module one error model (stable codes, transient marker) and ULID generation.

**Lane:** CORE · **Depends on:** [T01](T01-backend-skeleton.md) · **Size budget:** ~350 changed lines (hard limit 1000) · **Branch:** `ticket/core-01`

**Spec:** ov §5 (IDs, API errors), v1 §5.1 (failure codes, transient exceptions)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/common/src/main/java/dev/emitkit/common/error/ErrorCode.java`
- Create: `.../common/error/AppException.java`, `TransientException.java`
- Create: `.../common/id/Ids.java`
- Test: `backend/common/src/test/java/dev/emitkit/common/error/ErrorCodeTest.java`, `.../id/IdsTest.java`

## Interfaces

- **Produces:**
```java
public enum ErrorCode {
  // value = HTTP status used by the API's ProblemDetailsAdvice
  SPEC_CONFLICT(409), SPEC_NOT_FOUND(404), SPEC_TOO_LARGE(413), SPEC_INVALID(422),
  LINT_FAILED(422), BREAKING_BLOCKED(422), GENERATOR_ERROR(500), CHECK_FAILED(422),
  SANDBOX_TIMEOUT(504), STORAGE_ERROR(503), VCS_CONFLICT(409), VCS_AUTH(401),
  VCS_RATE_LIMITED(429), SECRETS_UNAVAILABLE(503), FORBIDDEN(403), NOT_FOUND(404),
  VALIDATION_FAILED(400), CONFLICT(409), RATE_LIMITED(429), NOT_IMPLEMENTED(501), INTERNAL(500);
  public int httpStatus();
}
public class AppException extends RuntimeException {
  public AppException(ErrorCode code, String detail);
  public AppException(ErrorCode code, String detail, Map<String, Object> extra, Throwable cause);
  public ErrorCode code(); public Map<String, Object> extra();   // extra → Problem Details properties
}
public class TransientException extends AppException { /* same ctors; worker retries these */ }
public final class Ids { public static String newUlid(); }   // 26-char Crockford base32, monotonic
```
Dependency: `com.github.f4b6a3:ulid-creator` (use `UlidCreator.getMonotonicUlid()`).

## Tasks

### Task 1: Error codes

- [ ] **Step 1: Write the failing test**

```java
@Test void everyCodeHasAnHttpStatus() {
  for (ErrorCode c : ErrorCode.values()) assertThat(c.httpStatus()).isBetween(400, 599);
}
@Test void specConflictIs409() { assertThat(ErrorCode.SPEC_CONFLICT.httpStatus()).isEqualTo(409); }
@Test void transientIsAnAppException() {
  var e = new TransientException(ErrorCode.STORAGE_ERROR, "s3 down");
  assertThat(e).isInstanceOf(AppException.class);
  assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_ERROR);
  assertThat(e.extra()).isEmpty();
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :common:test --tests '*ErrorCodeTest'`

- [ ] **Step 3: Implement** — `ErrorCode`, `AppException` (`extra` is an unmodifiable copy, never null), `TransientException`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :common:test --tests '*ErrorCodeTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(common): error codes and exception types"`

### Task 2: ULID ids

- [ ] **Step 1: Write the failing test**

```java
@Test void ulidIs26CrockfordChars() {
  assertThat(Ids.newUlid()).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
}
@Test void idsAreMonotonicWithinAMillisecond() {
  var ids = IntStream.range(0, 1000).mapToObj(i -> Ids.newUlid()).toList();
  assertThat(ids).isSorted().doesNotHaveDuplicates();
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :common:test --tests '*IdsTest'`

- [ ] **Step 3: Implement** — `Ids.newUlid()` → `UlidCreator.getMonotonicUlid().toString()`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :common:test --tests '*IdsTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(common): ULID ids"`

---

## Done when

- [ ] All tasks above are committed on `ticket/core-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

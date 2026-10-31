# API-00 · api-app skeleton

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A bootable api-app with the two security filter chains, CSRF, CORS allowlist, Problem Details errors, springdoc and health.

**Lane:** CORE · **Depends on:** [CORE-01](CORE-01-common-errors-ids.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/api-00`

**Spec:** ov §3 security baseline, §3.2 health, §5 errors; v1 §8 (public endpoints)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/ApiApplication.java`
- Create: `.../api/config/SecurityConfig.java` (two `SecurityFilterChain` beans), `CorsProperties.java`, `OpenApiConfig.java`
- Create: `.../api/error/ProblemDetailsAdvice.java`
- Create: `backend/api-app/src/main/resources/application.yml`, `application-dev.yml`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/ProblemDetailsAdviceTest.java`, `SecurityConfigTest.java`, `HealthTest.java`; test-only `TestController` under `src/test`

## Interfaces

- **Consumes:** `AppException`, `ErrorCode` (CORE-01).
- **Produces:**
- `SecurityFilterChain webhookChain` — `@Order(1)`, `securityMatcher("/webhooks/**")`, stateless, CSRF off, `permitAll` (HMAC checked in HOOK-01).
- `SecurityFilterChain appChain` — `@Order(2)`: CSRF with `CookieCsrfTokenRepository.withHttpOnlyFalse()` (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`); public: `/v3/api-docs/**`, `/swagger-ui/**`, `/login/**`, `/oauth2/**`; everything under `/api/v1/**` authenticated; unauthenticated API calls get **401 Problem Details** (not a redirect). `oauth2Login` is added by AUTH-01.
- `emitkit.cors.allowed-origins: List<String>` (credentials allowed, methods GET/POST/PUT/PATCH/DELETE).
- `ProblemDetailsAdvice`: `AppException` → status `code.httpStatus()`, body `{type: "https://emitkit.dev/errors/{code}", title, status, detail, code, ...extra}`; `MethodArgumentNotValidException` → `VALIDATION_FAILED` with `errors: [{field, message}]`; `AccessDeniedException` → `FORBIDDEN`; anything else → `INTERNAL` with no stack trace or message leak.
- Actuator: `management.server.port=8081`, only `health` exposed. Spring Session Redis (`spring.session.redis.namespace=emitkit`).

## Tasks

### Task 1: Problem Details

- [ ] **Step 1: Write the failing test**

```java
@WebMvcTest(TestController.class) @Import(ProblemDetailsAdvice.class)
class ProblemDetailsAdviceTest {
  @Test void appException_mapsToProblemWithCode() throws Exception {
    mvc.perform(get("/test/conflict")).andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("SPEC_CONFLICT"))
      .andExpect(jsonPath("$.type").value("https://emitkit.dev/errors/SPEC_CONFLICT"))
      .andExpect(jsonPath("$.currentSha").value("abc"));   // from extra
  }
  @Test void validationError_listsFields() { /* POST invalid body → 400, code VALIDATION_FAILED, errors[0].field */ }
  @Test void unexpectedError_hidesMessage() { /* throws RuntimeException("db password=x") → 500, body has no "password" */ }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*ProblemDetailsAdviceTest'`

- [ ] **Step 3: Implement** — `ProblemDetailsAdvice` extends `ResponseEntityExceptionHandler`, uses Spring's `ProblemDetail`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*ProblemDetailsAdviceTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): problem details error mapping"`

### Task 2: Security chains, CORS, CSRF

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest @AutoConfigureMockMvc
class SecurityConfigTest {
  @Test void apiWithoutSession_is401Problem() { /* GET /api/v1/test → 401, jsonPath $.status 401 */ }
  @Test @WithMockUser void postWithoutCsrf_is403() { }
  @Test @WithMockUser void postWithCsrf_isOk() { /* .with(csrf()) */ }
  @Test void webhookPathNeedsNoCsrfOrSession() { /* POST /webhooks/test → 200 */ }
  @Test void corsAllowedOrigin_getsHeader() { /* Origin: http://localhost:5173 → Access-Control-Allow-Origin echoed */ }
  @Test void corsForeignOrigin_rejected() { /* Origin: https://evil.example → 403 */ }
  @Test void apiDocsArePublic() { /* GET /v3/api-docs → 200 */ }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*SecurityConfigTest'`

- [ ] **Step 3: Implement** — `SecurityConfig` per the Interfaces block. Tests use Testcontainers Redis via `@ServiceConnection` (shared `RedisTestConfig` in `src/test`).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*SecurityConfigTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): security filter chains, csrf and cors"`

### Task 3: Health on management port

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")
class HealthTest {
  @Test void healthIsUpOnManagementPort() { /* GET http://localhost:{mgmtPort}/actuator/health → 200 UP */ }
  @Test void otherActuatorEndpointsNotExposed() { /* /actuator/env → 404 */ }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*HealthTest'`

- [ ] **Step 3: Implement** — actuator config in `application.yml`; Logback pattern includes `%X{runId}` and `%X{orgId}` (ov §3.2).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*HealthTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): health endpoint on management port"`

---

## Done when

- [ ] All tasks above are committed on `ticket/api-00`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

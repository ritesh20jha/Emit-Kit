# CON-01 · Contract: me, orgs, members

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the REST contract for me, orgs, members so frontend and backend work in parallel.

**Lane:** API · **Depends on:** [API-00](API-00-api-skeleton.md), [T04](T04-frontend-scaffold.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/con-01`

**Spec:** v1 §8 rows listed below; ov §5 (pagination, errors)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: controllers + DTO records under `backend/api-app/src/main/java/dev/emitkit/api/<feature>/` (see Interfaces)
- Modify: `frontend/openapi.json` (exported), `frontend/src/lib/api/generated/schema.d.ts` (generated) — both excluded from PR size
- Create: `backend/api-app/src/test/java/dev/emitkit/api/OpenApiSnapshotTest.java`, `scripts/export-openapi.sh` (boots api-app with `test` profile, fetches `/v3/api-docs`, pretty-prints with sorted keys), `frontend` script `pnpm gen:api` (`openapi-typescript ../frontend/openapi.json -o src/lib/api/generated/schema.d.ts`)
- Create: `.github/workflows/backend.yml` step: run `scripts/export-openapi.sh` and `git diff --exit-code frontend/openapi.json frontend/src/lib/api/generated`

**Contract ticket rules (CON-01..03):** controllers are real `@RestController`s with final paths, request/response records, `@Valid` constraints and springdoc annotations; every method body is `throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented")` (501). Feature tickets replace bodies without changing the DTOs; changing a DTO later means regenerating `openapi.json` + client in that PR.

## Interfaces

- **Consumes:** `ProblemDetailsAdvice`, security chains (API-00).
- **Produces:**
**Endpoints:**
- `GET /api/v1/me` → `MeResponse` · `GET /api/v1/me/vcs-quota` → `QuotaResponse` · `POST /api/v1/me/sync-orgs` → `204`
- `GET /api/v1/orgs` → `List<OrgSummary>` · `GET /api/v1/orgs/{orgId}` → `OrgDetail`
- `GET /api/v1/orgs/{orgId}/members` → `List<MemberResponse>` · `PATCH /api/v1/orgs/{orgId}/members/{userId}` body `UpdateRoleRequest` → `MemberResponse`

**DTOs (records, camelCase JSON):**
```java
record MeResponse(String id, String login, String name, String avatarUrl, List<OrgMembership> orgs) {}
record OrgMembership(String orgId, String slug, String name, boolean isPersonal, Role role) {}
record QuotaResponse(int limit, int remaining, Instant resetAt) {}
record OrgSummary(String id, String slug, String name, boolean isPersonal, Role role, int memberCount) {}
record OrgDetail(String id, String slug, String name, boolean isPersonal, String vcsOrgId, int memberCount, Role role) {}
record MemberResponse(String userId, String login, String name, String avatarUrl, Role role, RoleSource roleSource) {}
record UpdateRoleRequest(@NotNull Role role) {}
record Page<T>(List<T> items, @Nullable String nextCursor) {}   // shared, used by CON-02/03
```

## Tasks

### Task 1: Contract + snapshot

- [ ] **Step 1: Write the failing test**

```java
class CON01ContractTest {   // @WebMvcTest + @WithMockUser
  @Test void meIs501UntilImplemented() { mvc.perform(get("/api/v1/me")).andExpect(status().is(501)).andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED")); }
  @Test void patchRoleValidatesBody() { /* {} → 400 VALIDATION_FAILED */ }
}
class OpenApiSnapshotTest {
  @Test void apiDocsMatchCommittedFile() { assertThat(normalize(get("/v3/api-docs"))).isEqualTo(normalize(read("../../frontend/openapi.json"))); }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*CON01ContractTest' --tests '*OpenApiSnapshotTest'`

- [ ] **Step 3: Implement** — controllers + DTOs; then `scripts/export-openapi.sh && (cd frontend && pnpm gen:api && pnpm tsc --noEmit)`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*CON01ContractTest' --tests '*OpenApiSnapshotTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): contract: me, orgs, members"`

---

## Done when

- [ ] All tasks above are committed on `ticket/con-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

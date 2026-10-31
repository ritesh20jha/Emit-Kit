# VCS-02 · GitHub client: identity, repos, files

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitHub implementation of org/repo listing, repo creation, file get/put and rate-limit reading, with error mapping.

**Lane:** VCS · **Depends on:** [VCS-01](VCS-01-interfaces.md) · **Size budget:** ~750 changed lines (hard limit 1000) · **Branch:** `ticket/vcs-02`

**Spec:** v1 §7

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/vcs/src/main/java/dev/emitkit/vcs/github/GitHubVcsProvider.java`, `GitHubVcsClient.java`, `GitHubErrors.java`, `GitHubProperties.java` (`emitkit.github.api-url` default `https://api.github.com`, `cache-dir`, `cache-size-mb`)
- Modify: `backend/vcs/build.gradle.kts` (`org.kohsuke:github-api`, `okhttp`, WireMock test dep)
- Test: `backend/vcs/src/test/java/dev/emitkit/vcs/github/GitHubVcsClientFilesTest.java`, `GitHubContractTest.java` (extends `VcsClientContractTest` with WireMock stubs), `src/test/resources/wiremock/` JSON fixtures

## Interfaces

- **Produces:**
- `GitHubVcsProvider implements VcsProvider` — `forUser(token)` builds `GitHub` with `GitHubBuilder().withEndpoint(apiUrl).withOAuthToken(token).withConnector(new OkHttpGitHubConnector(cachedClient))`; `forProject(userId)` uses `TokenSource`.
- `listOrgs()` = user's orgs (role from `/user/memberships/orgs`: `admin` → `OWNER`, else `MEMBER`) **plus** the personal account as `VcsOrg(isPersonal=true, role=OWNER)`.
- `listRepos()` = repos the token can push to, sorted by `pushedAt` desc, max 300.
- Error mapping in `GitHubErrors.map(Exception)`: 401 / 403 without rate-limit headers → `VcsAuthException`; 403/429 with `X-RateLimit-Remaining: 0` → `VcsRateLimitException(resetAt)`; 404 → `VcsNotFoundException`; 409/422 on contents PUT → `VcsConflictException`; 5xx / IO → `VcsServerException`.
- Every response's rate-limit headers are passed to an optional `RateLimitListener` (`void onHeaders(int remaining, Instant reset)`) — VCS-05 plugs Redis in.

## Tasks

### Task 1: Files, repos, orgs over WireMock

- [ ] **Step 1: Write the failing test**

```java
@WireMockTest class GitHubVcsClientFilesTest {
  @Test void getFile_decodesBase64Content() { stubContents("acme/api", "openapi.yaml", "b3BlbmFwaTogMy4xLjA=", "sha1"); assertThat(client.getFile("acme/api","openapi.yaml","main")).isEqualTo(new FileContent("openapi: 3.1.0", "sha1")); }
  @Test void putFile_shaMismatch409_isConflict() { stubFor(put(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml")).willReturn(status(409))); assertThatThrownBy(...).isInstanceOf(VcsConflictException.class); }
  @Test void unauthorized_isAuthException() { }
  @Test void rateLimited_isRateLimitException_withReset() { /* 403 + X-RateLimit-Remaining: 0 + X-RateLimit-Reset: 1900000000 */ }
  @Test void serverError_isTransient() { /* 502 → VcsServerException */ }
  @Test void listOrgs_includesPersonalAccountAsOwner() { }
  @Test void rateLimitHeadersReachListener() { }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :vcs:test --tests '*GitHub*'`

- [ ] **Step 3: Implement** — `GitHubVcsClient` methods listed; `getFile` uses the contents API (files ≤ 1 MB) and falls back to the blob API for larger ones (FetchSpec allows up to 2 MB).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :vcs:test --tests '*GitHub*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(vcs): github client for repos and files"`

---

## Done when

- [ ] All tasks above are committed on `ticket/vcs-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

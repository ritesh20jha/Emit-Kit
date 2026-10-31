# VCS-01 · VCS interfaces + in-memory fake

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define VcsProvider/VcsClient exactly as the spec, the VCS exception types, and an in-memory fake other lanes test against.

**Lane:** VCS · **Depends on:** [CORE-01](CORE-01-common-errors-ids.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/vcs-01`

**Spec:** v1 §7

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/vcs/src/main/java/dev/emitkit/vcs/` — `VcsProvider.java`, `VcsProviderId.java`, `VcsClient.java`, `WebhookVerifier.java`, records `VcsOrg`, `VcsRepo`, `FileContent`, `CommitRef`, `PullRequest`, `Webhook`, `RateLimit`, `CommitFile`
- Create: `backend/vcs/src/main/java/dev/emitkit/vcs/error/` — `VcsConflictException`, `VcsAuthException`, `VcsNotFoundException`, `VcsRateLimitException`, `VcsServerException`
- Create: `backend/vcs/src/testFixtures/java/dev/emitkit/vcs/fake/InMemoryVcsClient.java`, `backend/vcs/src/testFixtures/java/dev/emitkit/vcs/contract/VcsClientContractTest.java` (abstract)
- Test: `backend/vcs/src/test/java/dev/emitkit/vcs/fake/InMemoryVcsClientTest.java` (extends the contract test)

## Interfaces

- **Produces:**
`VcsProvider` and `VcsClient` — **copy the signatures from v1 §7 verbatim**, with these two adjustments so `vcs` doesn't depend on `generators/core` or `domain` (ov §2 rule `vcs → common`):
- `commitTree(String repo, String branch, List<CommitFile> files, String message)` with `record CommitFile(String path, String content)`; the worker maps `GeneratedFile` → `CommitFile`.
- `VcsProvider.forProject(String creatorUserId)` instead of `forProject(Project)`; token lookup is a `TokenSource` interface (`String accessTokenFor(String userId)`) implemented in api/worker (AUTH-01).

```java
public record VcsOrg(String id, String login, String name, boolean isPersonal, VcsRole role) { public enum VcsRole { OWNER, MEMBER } }
public record VcsRepo(String fullName, String defaultBranch, boolean isPrivate, Instant pushedAt, String owner) {}
public record FileContent(String content, String sha) {}
public record CommitRef(String sha, String htmlUrl) {}
public record PullRequest(int number, String htmlUrl, String headRef) {}
public record Webhook(long id, String url) {}
public record RateLimit(int limit, int remaining, Instant resetAt) {}
public interface WebhookVerifier { boolean verify(byte[] body, String signatureHeader, String secret); }
```
- `createTag` returns `boolean` (false if the tag already existed) — change from `void` in §7, needed by HOOK-02.
- Exceptions: `VcsConflictException(CONFLICT→VCS_CONFLICT)`, `VcsAuthException(VCS_AUTH)`, `VcsNotFoundException(NOT_FOUND)` extend `AppException`; `VcsRateLimitException(VCS_RATE_LIMITED, resetAt)` and `VcsServerException(INTERNAL)` extend `TransientException`.
- `InMemoryVcsClient`: repos/branches/files in maps; `failNext(RuntimeException)` to inject errors; records calls (`calls()`), e.g. to assert "one PR opened".

## Tasks

### Task 1: Interfaces + fake against a shared contract

- [ ] **Step 1: Write the failing test**

```java
public abstract class VcsClientContractTest {
  protected abstract VcsClient client();   // repo "acme/api" exists with main branch and openapi.yaml
  @Test void getFile_returnsContentAndSha() { var f = client().getFile("acme/api", "openapi.yaml", "main"); assertThat(f.sha()).isNotBlank(); }
  @Test void getFile_missing_throwsNotFound() { assertThatThrownBy(() -> client().getFile("acme/api", "nope.yaml", "main")).isInstanceOf(VcsNotFoundException.class); }
  @Test void putFile_withStaleSha_throwsConflict() {
    var f = client().getFile("acme/api", "openapi.yaml", "main");
    client().putFile("acme/api", "openapi.yaml", "v2", f.sha(), "m");
    assertThatThrownBy(() -> client().putFile("acme/api", "openapi.yaml", "v3", f.sha(), "m")).isInstanceOf(VcsConflictException.class);
  }
  @Test void commitTree_thenListBlobShas() {
    client().createBranch("acme/api", "b1", head());
    client().commitTree("acme/api", "b1", List.of(new CommitFile("out/a.ts", "x")), "m");
    assertThat(client().listBlobShas("acme/api", "b1", "out/")).containsEntry("out/a.ts", GitBlob.sha("x"));
  }
  @Test void createTag_twice_secondReturnsFalse() { }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :vcs:test --tests '*InMemoryVcsClientTest'`

- [ ] **Step 3: Implement** — the interfaces/records/exceptions; `GitBlob.sha(String)` in `common`-free `dev.emitkit.vcs.GitBlob` = hex SHA-1 of `"blob " + byteLength + "\0" + content` (also used by WRK-08); `InMemoryVcsClient` uses `GitBlob.sha` for file shas so it matches GitHub.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :vcs:test --tests '*InMemoryVcsClientTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(vcs): vcs interfaces, fake and contract tests"`

---

## Done when

- [ ] All tasks above are committed on `ticket/vcs-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

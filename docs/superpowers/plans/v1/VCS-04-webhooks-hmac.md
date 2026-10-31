# VCS-04 · Webhooks register/delete + HMAC verifier

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register and delete repo webhooks and verify GitHub's X-Hub-Signature-256 in constant time.

**Lane:** VCS · **Depends on:** [VCS-02](VCS-02-github-files.md) · **Size budget:** ~400 changed lines (hard limit 1000) · **Branch:** `ticket/vcs-04`

**Spec:** v1 §7, §9

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Modify: `backend/vcs/src/main/java/dev/emitkit/vcs/github/GitHubVcsClient.java` (`registerWebhook`, `deleteWebhook`)
- Create: `backend/vcs/src/main/java/dev/emitkit/vcs/github/GitHubWebhookVerifier.java`
- Test: `backend/vcs/src/test/java/dev/emitkit/vcs/github/GitHubWebhookVerifierTest.java`, `GitHubWebhooksTest.java`

## Interfaces

- **Produces:**
- `registerWebhook(repo, url, secret, events)` → `POST /repos/{repo}/hooks` with `content_type=json`, `insecure_ssl=0`, `active=true`; returns `Webhook(id, url)`.
- `deleteWebhook(repo, id)`; 404 → no-op.
- `GitHubWebhookVerifier.verify(body, header, secret)`: header must be `sha256=<64 hex>`; compute HMAC-SHA256(secret, body) and compare with `MessageDigest.isEqual`; any malformed header → `false` (never throws).

## Tasks

### Task 1: Verifier

- [ ] **Step 1: Write the failing test**

```java
// GitHub docs test vector: secret "It's a Secret to Everybody", payload "Hello, World!"
@Test void knownVector_verifies() { assertThat(v.verify("Hello, World!".getBytes(UTF_8), "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17", "It's a Secret to Everybody")).isTrue(); }
@Test void changedBody_fails() { }
@Test void missingPrefix_fails() { }
@Test void wrongLengthHex_fails() { }
@Test void nullHeader_fails() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :vcs:test --tests '*GitHubWebhookVerifierTest'`

- [ ] **Step 3: Implement** — `GitHubWebhookVerifier`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :vcs:test --tests '*GitHubWebhookVerifierTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(vcs): webhook signature verifier"`

### Task 2: Register / delete

- [ ] **Step 1: Write the failing test**

```java
@Test void register_sendsEventsAndSecret() { /* verify request body has events ["push","pull_request"], config.secret, config.content_type json */ }
@Test void delete_missing_isNoOp() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :vcs:test --tests '*GitHubWebhooksTest'`

- [ ] **Step 3: Implement** — the two client methods.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :vcs:test --tests '*GitHubWebhooksTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(vcs): register and delete webhooks"`

---

## Done when

- [ ] All tasks above are committed on `ticket/vcs-04`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

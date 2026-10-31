# HOOK-01 · Webhook endpoint core

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Receive GitHub webhooks: verify the signature against the right project, de-duplicate deliveries, and fail with 503 when secrets can't be read.

**Lane:** API · **Depends on:** [VCS-04](VCS-04-webhooks-hmac.md), [PRJ-01](PRJ-01-create-existing.md), [CORE-02](CORE-02-secret-cipher.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/hook-01`

**Spec:** v1 §9; ov §3.1 failure behaviour; README Review Focus #1

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/webhooks/GitHubWebhookController.java`, `WebhookDispatcher.java`, `DeliveryDeduper.java`, `WebhookEvent.java`
- Test: `backend/api-app/src/test/java/dev/emitkit/api/webhooks/HOOK01WebhookIT.java`

## Interfaces

- **Produces:**
- `POST /webhooks/github` reads the raw body as `byte[]` (`@RequestBody byte[]`), header `X-GitHub-Event`, `X-GitHub-Delivery`, `X-Hub-Signature-256`.
- Parse `repository.full_name` with a minimal Jackson tree read; candidates = `projectRepository.findByRepoFullName(name)` (all orgs, not deleted). Decrypt each candidate's secret (`decryptBatch`); the first that verifies is the match. None → 401. `SecretBackendException` → 503 (`SECRETS_UNAVAILABLE`).
- De-dup: Redis `SET webhook:delivery:{id} 1 NX EX 86400` **after** signature verification; already present → 200 `{"status":"duplicate"}`.
- `ping` → 200 `{"status":"pong"}`. Unknown events → 200 `{"status":"ignored","reason":"event"}`. Dispatch to `WebhookDispatcher.handle(Project, WebhookEvent)` (push / pull_request handled in HOOK-02; stub returns ignored).
- Response always within 10 s (no blocking calls except two GitHub calls in HOOK-02).

## Tasks

### Task 1: Webhook endpoint core

- [ ] **Step 1: Write the failing test**

```java
@Test void matchesOneOfTwoCandidateProjects() { }
@Test void badSignature_401() { }
@Test void duplicateDelivery_isIgnored() { deliver(d1); deliver(d1); assertThat(dispatcher.calls()).hasSize(1); assertThat(lastBody()).contains("duplicate"); }
@Test void vaultDown_503() { cipher.failWith(new SecretBackendException("sealed")); assertThat(deliver(d1).status()).isEqualTo(503); }
@Test void noCsrfOrSessionNeeded() { }
@Test void ping_pong() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*HOOK01*'`

- [ ] **Step 3: Implement** — as listed.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*HOOK01*'`

- [ ] **Step 5: Commit** — `git commit -m "feat(api): webhook endpoint core"`

---

## Done when

- [ ] All tasks above are committed on `ticket/hook-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

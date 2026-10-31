# VCS-05 · Rate-limit tracking

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record GitHub quota per user in Redis and apply wait-or-fail policies for background vs interactive calls.

**Lane:** VCS · **Depends on:** [VCS-02](VCS-02-github-files.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/vcs-05`

**Spec:** v1 §7 rate limits

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/vcs/src/main/java/dev/emitkit/vcs/ratelimit/RateLimitStore.java` (interface), `RedisRateLimitStore.java`, `RateLimitPolicy.java`, `RateLimitedVcsClient.java`
- Modify: `backend/vcs/build.gradle.kts` (`spring-data-redis` compileOnly — the Redis store is wired by the apps)
- Test: `backend/vcs/src/test/java/dev/emitkit/vcs/ratelimit/RateLimitedVcsClientTest.java`, `RedisRateLimitStoreIT.java`

## Interfaces

- **Produces:**
```java
public interface RateLimitStore { void record(String userId, int remaining, Instant resetAt); Optional<RateLimit> get(String userId); }
public enum RateLimitPolicy { BACKGROUND, INTERACTIVE }
public final class RateLimitedVcsClient implements VcsClient {   // decorator
  public RateLimitedVcsClient(VcsClient delegate, String userId, RateLimitStore store, RateLimitPolicy policy, Clock clock, Sleeper sleeper);
}
```
- Redis key `ratelimit:{userId}` hash `{remaining, resetAt}` with TTL until reset + 60 s.
- Before each call: if stored `remaining < 100` and reset is in the future → `INTERACTIVE`: throw `VcsRateLimitException(resetAt)` (API maps to 429 `VCS_RATE_LIMITED` with `resetAt`); `BACKGROUND`: if reset ≤ 5 min away, `sleeper.sleep(until reset)` then call, else throw `VcsRateLimitException`.
- `VcsProvider.forUser(token, userId, policy)` / `forProject(userId, policy)` overloads return the decorated client (add to `VcsProvider` in this PR).

## Tasks

### Task 1: Policy decorator

- [ ] **Step 1: Write the failing test**

```java
@Test void interactive_lowQuota_failsFast() {
  store.record("u1", 99, clock.instant().plusSeconds(600));
  assertThatThrownBy(() -> client(INTERACTIVE).listRepos()).isInstanceOf(VcsRateLimitException.class);
  assertThat(fake.calls()).isEmpty();
}
@Test void background_lowQuota_resetSoon_waitsThenCalls() { store.record("u1", 99, clock.instant().plusSeconds(2)); client(BACKGROUND).listRepos(); assertThat(sleeper.slept()).isEqualTo(Duration.ofSeconds(2)); assertThat(fake.calls()).hasSize(1); }
@Test void background_lowQuota_resetFar_throwsTransient() { store.record("u1", 99, clock.instant().plusSeconds(600)); assertThatThrownBy(...).isInstanceOf(VcsRateLimitException.class).isInstanceOf(TransientException.class); }
@Test void enoughQuota_callsThrough() { }
@Test void resetInPast_ignoresStoredValue() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :vcs:test --tests '*RateLimitedVcsClientTest'`

- [ ] **Step 3: Implement** — the decorator (in-memory `RateLimitStore` in tests).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :vcs:test --tests '*RateLimitedVcsClientTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(vcs): rate-limit policies"`

### Task 2: Redis store

- [ ] **Step 1: Write the failing test**

```java
@Test void recordThenGet_andExpires() { /* Testcontainers redis:7: record → get present; TTL set ≈ reset+60s */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :vcs:test --tests '*RedisRateLimitStoreIT'`

- [ ] **Step 3: Implement** — `RedisRateLimitStore(StringRedisTemplate)`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :vcs:test --tests '*RedisRateLimitStoreIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(vcs): redis rate-limit store"`

---

## Done when

- [ ] All tasks above are committed on `ticket/vcs-05`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

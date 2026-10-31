# AUTH-01 · GitHub OAuth login + /me

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sign in with GitHub, store the encrypted token, keep the session in Redis, and serve /me.

**Lane:** API · **Depends on:** [CON-01](CON-01-contract-me-orgs.md), [DB-01](DB-01-identity-schema.md), [CORE-02](CORE-02-secret-cipher.md) · **Size budget:** ~700 changed lines (hard limit 1000) · **Branch:** `ticket/auth-01`

**Spec:** ov §3 security baseline, §3.1; v1 §8 `/me`; v1 §10.1 `/login?next=`

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/api-app/src/main/java/dev/emitkit/api/auth/GitHubUserService.java` (`OAuth2UserService`), `LoginSuccessHandler.java`, `CurrentUser.java` (resolver for `@AuthenticationPrincipal`), `DbTokenSource.java` (implements VCS `TokenSource`)
- Modify: `backend/api-app/src/main/java/dev/emitkit/api/config/SecurityConfig.java` (`oauth2Login`, logout `POST /api/v1/logout` → 204)
- Modify: `backend/api-app/src/main/java/dev/emitkit/api/me/MeController.java` (implement `GET /me`)
- Test: `backend/api-app/src/test/java/dev/emitkit/api/auth/OAuthLoginIT.java` (WireMock as GitHub authorization + token + user endpoints), `backend/api-app/src/test/java/dev/emitkit/api/auth/LoginSuccessHandlerTest.java`

## Interfaces

- **Produces:**
- Scopes (exact): `repo, read:org, workflow, user:email, write:repo_hook`. Registration id `github`; endpoints overridable for tests via `spring.security.oauth2.client.provider.github.*`.
- On login: upsert `users` by `github_id`; upsert `oauth_accounts(provider="github", provider_account_id=githubId)`; **the account id is assigned before encrypting** and token stored as `cipher.encrypt(token, new SecretContext("oauth_accounts","access_token_enc", accountId))`; `scopes` saved; then publish `UserLoggedInEvent(userId)` (AUTH-02 listens).
- Principal: `EmitKitUser(String userId, String login)` stored in the session; the GitHub token is **not** kept in the session.
- `DbTokenSource.accessTokenFor(userId)` decrypts on demand (no caching).
- Redirect: `GET /oauth2/authorization/github?next=/projects/x` → `next` stored in session; after success redirect to `{emitkit.web.base-url}{next}` only if `next` starts with `/` and not `//`; else `{base-url}/`.

## Tasks

### Task 1: Login flow

- [ ] **Step 1: Write the failing test**

```java
@Test void firstLogin_createsUserAndEncryptedToken() {
  loginAs(githubUser(42, "octo"), "gho_token1");
  var acc = oauth.findByProviderAndProviderAccountId("github", "42").orElseThrow();
  assertThat(acc.getAccessTokenEnc()).startsWith("local:v1:").doesNotContain("gho_token1");   // test profile uses LocalAesCipher
  assertThat(tokenSource.accessTokenFor(acc.getUserId())).isEqualTo("gho_token1");
}
@Test void secondLogin_updatesToken() { loginAs(u, "t1"); loginAs(u, "t2"); assertThat(tokenSource.accessTokenFor(id)).isEqualTo("t2"); assertThat(users.count()).isEqualTo(1); }
@Test void me_returnsUserAndOrgs() { }
@Test void sessionCookieFlags() { /* Set-Cookie has HttpOnly; Secure; SameSite=Lax */ }
@Test void logout_invalidatesSession() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*OAuthLoginIT'`

- [ ] **Step 3: Implement** — `GitHubUserService` wraps `DefaultOAuth2UserService`; cookie config via `server.servlet.session.cookie.*`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*OAuthLoginIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(auth): github oauth login"`

### Task 2: Safe redirect

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @CsvSource({"/projects/x,/projects/x", "//evil.com,/", "https://evil.com,/", ",/", "/\\evil.com,/"})
void redirectTarget(String next, String expectedPath) { assertThat(handler.target(next)).isEqualTo("http://localhost:5173" + expectedPath); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :api-app:test --tests '*LoginSuccessHandlerTest'`

- [ ] **Step 3: Implement** — `LoginSuccessHandler.target(String next)`.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :api-app:test --tests '*LoginSuccessHandlerTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(auth): safe post-login redirect"`

---

## Done when

- [ ] All tasks above are committed on `ticket/auth-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

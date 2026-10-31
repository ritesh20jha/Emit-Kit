# CORE-02 · SecretCipher (Vault Transit + local AES)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encrypt and decrypt every stored secret through Vault Transit with a per-row context; a local AES cipher exists only for unit tests.

**Lane:** CORE · **Depends on:** [CORE-01](CORE-01-common-errors-ids.md), [T03](T03-local-stack.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/core-02`

**Spec:** ov §3.1 (all of it)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/common/src/main/java/dev/emitkit/common/secret/SecretCipher.java`, `SecretContext.java`, `CipherItem.java`, `SecretBackendException.java`
- Create: `.../common/secret/VaultTransitCipher.java`, `LocalAesCipher.java`, `SecretCipherConfig.java`, `VaultProperties.java`
- Create: `.../common/secret/VaultHealthIndicator.java`
- Modify: `backend/common/build.gradle.kts` (add `spring-vault-core`, `spring-boot-starter` compileOnly for config/health, Testcontainers Vault test dep)
- Test: `backend/common/src/test/java/dev/emitkit/common/secret/VaultTransitCipherIT.java`, `LocalAesCipherTest.java`, `SecretCipherConfigTest.java`

## Interfaces

- **Consumes:** `ErrorCode.SECRETS_UNAVAILABLE`, `TransientException` (CORE-01); Vault setup from T03.
- **Produces:**
```java
public record SecretContext(String table, String column, String rowId) {
  public String encoded();   // base64("{table}:{column}:{rowId}")
}
public record CipherItem(String ciphertext, SecretContext ctx) {}
public interface SecretCipher {
  String encrypt(String plaintext, SecretContext ctx);   // "vault:v{n}:..."
  String decrypt(String ciphertext, SecretContext ctx);
  List<String> decryptBatch(List<CipherItem> items);     // one Transit call; same order as input
}
public class SecretBackendException extends TransientException { /* code SECRETS_UNAVAILABLE */ }
```
Properties `emitkit.vault.*`: `uri`, `role-id`, `secret-id-file`, `transit-key` (default `emitkit-secrets`), `cipher` (`vault` | `local`, default `vault`).
Health indicator name `vault` (encrypt+decrypt of a fixed value with context `health:check:0`).

## Tasks

### Task 1: Vault Transit cipher

- [ ] **Step 1: Write the failing test**

```java
@Testcontainers class VaultTransitCipherIT {
  @Container static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:<same tag as T03>")
      .withVaultToken("root")
      .withInitCommand("secrets enable transit",
          "write -f transit/keys/emitkit-secrets type=aes256-gcm96 derived=true");
  SecretContext ctx = new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000A");

  @Test void roundTrip() {
    String ct = cipher.encrypt("s3cret", ctx);
    assertThat(ct).startsWith("vault:v1:");
    assertThat(cipher.decrypt(ct, ctx)).isEqualTo("s3cret");
  }
  @Test void ciphertextFromAnotherRow_failsToDecrypt() {
    String ct = cipher.encrypt("s3cret", ctx);
    var other = new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000B");
    assertThatThrownBy(() -> cipher.decrypt(ct, other)).isInstanceOf(AppException.class);
  }
  @Test void decryptBatch_keepsOrder() { /* 3 items, different contexts → same 3 plaintexts in order */ }
  @Test void vaultDown_throwsSecretBackendException() {
    vault.stop();
    assertThatThrownBy(() -> cipher.encrypt("x", ctx)).isInstanceOf(SecretBackendException.class);
  }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :common:test --tests '*VaultTransitCipherIT'`

- [ ] **Step 3: Implement** — `VaultTransitCipher(VaultTransitOperations ops, String key)`: use `VaultTransitContext.builder().context(bytes)`; `decryptBatch` uses `ops.decrypt(key, List<Ciphertext>)`. Map `VaultException` with connection/5xx/sealed causes → `SecretBackendException`; a 400 from Vault (bad context) → `AppException(INTERNAL)`. Never put plaintext in exception messages or logs. (The test builds the cipher with a token-auth `VaultTemplate`; AppRole is wired in Task 3.)

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :common:test --tests '*VaultTransitCipherIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(common): vault transit secret cipher"`

### Task 2: Local AES cipher (tests only)

- [ ] **Step 1: Write the failing test**

```java
@Test void roundTrip_andContextBound() {
  var c = new LocalAesCipher();
  String ct = c.encrypt("x", ctx);
  assertThat(ct).startsWith("local:v1:");
  assertThat(c.decrypt(ct, ctx)).isEqualTo("x");
  assertThatThrownBy(() -> c.decrypt(ct, otherCtx)).isInstanceOf(AppException.class);
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :common:test --tests '*LocalAesCipherTest'`

- [ ] **Step 3: Implement** — AES/GCM/NoPadding, fixed 256-bit test key, random 12-byte IV, `ctx.encoded()` bytes as AAD; output `local:v1:` + base64(iv‖ct).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :common:test --tests '*LocalAesCipherTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(common): local AES cipher for tests"`

### Task 3: Config + profile guard + AppRole + health

- [ ] **Step 1: Write the failing test**

```java
@Test void localCipherUnderProdProfile_failsStartup() {
  new ApplicationContextRunner()
      .withUserConfiguration(SecretCipherConfig.class)
      .withPropertyValues("spring.profiles.active=prod", "emitkit.vault.cipher=local")
      .run(ctx -> assertThat(ctx).hasFailed()
          .getFailure().hasMessageContaining("LocalAesCipher is not allowed"));
}
@Test void localCipherUnderTestProfile_isUsed() { /* profile=test, cipher=local → bean is LocalAesCipher */ }
@Test void appRoleSecretIdIsReadFromFile(@TempDir Path dir) { /* writes secret-id file; VaultProperties.secretId() reads it, trimmed */ }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :common:test --tests '*SecretCipherConfigTest'`

- [ ] **Step 3: Implement** — `SecretCipherConfig` (`@AutoConfiguration`, registered in `META-INF/spring/...AutoConfiguration.imports`) builds a `VaultTemplate` with `AppRoleAuthentication` (role id from property, secret id from file) and picks the cipher by `emitkit.vault.cipher`; throws `IllegalStateException("LocalAesCipher is not allowed with profile prod/staging")`. `VaultHealthIndicator` reports DOWN with detail `sealed`/`unreachable`, never the value.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :common:test --tests '*SecretCipherConfigTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(common): secret cipher config and vault health"`

---

## Done when

- [ ] All tasks above are committed on `ticket/core-02`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

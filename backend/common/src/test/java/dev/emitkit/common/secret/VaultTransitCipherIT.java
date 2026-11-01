package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.net.URI;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

@Testcontainers
class VaultTransitCipherIT {

  private static final String KEY = "emitkit-secrets";

  @Container static VaultContainer<?> vault = newVault();

  private final SecretContext ctx = row("A");
  private final VaultTransitCipher cipher = cipherFor(vault);

  @Test
  void roundTrip() {
    String ct = cipher.encrypt("s3cret", ctx);
    assertThat(ct).startsWith("vault:v1:");
    assertThat(cipher.decrypt(ct, ctx)).isEqualTo("s3cret");
  }

  @Test
  void ciphertextFromAnotherRow_failsToDecrypt() {
    String ct = cipher.encrypt("s3cret", ctx);
    assertInternal(() -> cipher.decrypt(ct, row("B")));
  }

  @Test
  void decryptBatch_keepsOrder() {
    List<CipherItem> items =
        List.of(item("alpha", row("A")), item("bravo", row("B")), item("charlie", row("C")));

    assertThat(cipher.decryptBatch(items)).containsExactly("alpha", "bravo", "charlie");
  }

  @Test
  void decryptBatch_itemFailure_isInternalNotBackendFailure() {
    List<CipherItem> items = List.of(new CipherItem(cipher.encrypt("s3cret", ctx), row("B")));

    assertInternal(() -> cipher.decryptBatch(items));
  }

  @Test
  void decryptBatch_emptyInput_returnsEmptyWithoutCallingVault() {
    assertThat(stoppedCipher().decryptBatch(List.of())).isEmpty();
  }

  @Test
  void vaultDown_throwsSecretBackendException() {
    VaultTransitCipher down = stoppedCipher();

    assertThatThrownBy(() -> down.encrypt("x", ctx)).isInstanceOf(SecretBackendException.class);
  }

  private CipherItem item(String plaintext, SecretContext itemCtx) {
    return new CipherItem(cipher.encrypt(plaintext, itemCtx), itemCtx);
  }

  private static void assertInternal(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isNotInstanceOf(SecretBackendException.class)
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL));
  }

  /** A dedicated container, so stopping it cannot break the shared one used by the other tests. */
  private static VaultTransitCipher stoppedCipher() {
    try (VaultContainer<?> dedicated = newVault()) {
      dedicated.start();
      VaultTransitCipher cipher = cipherFor(dedicated);
      dedicated.stop();
      return cipher;
    }
  }

  private static SecretContext row(String id) {
    return new SecretContext("projects", "webhook_secret_enc", id);
  }

  private static VaultContainer<?> newVault() {
    return new VaultContainer<>("hashicorp/vault:2.2.1")
        .withVaultToken("root")
        .withInitCommand(
            "secrets enable transit",
            "write -f transit/keys/" + KEY + " type=aes256-gcm96 derived=true");
  }

  private static VaultTransitCipher cipherFor(VaultContainer<?> container) {
    var endpoint = VaultEndpoint.from(URI.create(container.getHttpHostAddress()));
    var template = new VaultTemplate(endpoint, new TokenAuthentication("root"));
    return new VaultTransitCipher(template.opsForTransit(), KEY);
  }
}

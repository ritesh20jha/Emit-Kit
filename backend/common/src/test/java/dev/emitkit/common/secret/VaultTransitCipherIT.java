package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.net.URI;
import java.util.List;
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

  private final SecretContext ctx =
      new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000A");
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
    var other = new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000B");
    assertThatThrownBy(() -> cipher.decrypt(ct, other))
        .isNotInstanceOf(SecretBackendException.class)
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL));
  }

  @Test
  void decryptBatch_keepsOrder() {
    var ctxA = new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000A");
    var ctxB = new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000B");
    var ctxC = new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000C");
    List<CipherItem> items =
        List.of(
            new CipherItem(cipher.encrypt("alpha", ctxA), ctxA),
            new CipherItem(cipher.encrypt("bravo", ctxB), ctxB),
            new CipherItem(cipher.encrypt("charlie", ctxC), ctxC));

    assertThat(cipher.decryptBatch(items)).containsExactly("alpha", "bravo", "charlie");
  }

  @Test
  void decryptBatch_itemFailure_isInternalNotBackendFailure() {
    var other = new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000B");
    List<CipherItem> items = List.of(new CipherItem(cipher.encrypt("s3cret", ctx), other));

    assertThatThrownBy(() -> cipher.decryptBatch(items))
        .isNotInstanceOf(SecretBackendException.class)
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL));
  }

  @Test
  void decryptBatch_emptyInput_returnsEmptyWithoutCallingVault() {
    try (VaultContainer<?> dedicated = newVault()) {
      dedicated.start();
      VaultTransitCipher down = cipherFor(dedicated);
      dedicated.stop();

      assertThat(down.decryptBatch(List.of())).isEmpty();
    }
  }

  @Test
  void vaultDown_throwsSecretBackendException() {
    // Dedicated container: stopping it must not break the shared one used by the other tests.
    try (VaultContainer<?> dedicated = newVault()) {
      dedicated.start();
      VaultTransitCipher down = cipherFor(dedicated);
      dedicated.stop();

      assertThatThrownBy(() -> down.encrypt("x", ctx)).isInstanceOf(SecretBackendException.class);
    }
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

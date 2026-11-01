package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.vault.authentication.VaultLoginException;
import org.springframework.vault.client.VaultClientResponseException;
import org.springframework.web.client.RestClientResponseException;

class VaultHealthIndicatorTest {

  private static final String LEAKY_MESSAGE = "connect to vault.internal:8200 refused";

  @Test
  void up_whenRoundTripSucceeds() {
    var cipher = new StubCipher();
    Health health = new VaultHealthIndicator(cipher).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(cipher.plaintexts).hasSize(1);
  }

  @Test
  void down_unreachable_whenBackendFails() {
    var cipher = new StubCipher();
    cipher.failure = new SecretBackendException(LEAKY_MESSAGE, new RuntimeException("io"));

    Health health = new VaultHealthIndicator(cipher).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "unreachable");
    assertNoLeak(health, cipher);
  }

  @Test
  void down_sealed_whenBackendReturns503() {
    var cipher = new StubCipher();
    cipher.failure = new SecretBackendException(LEAKY_MESSAGE, response(503, "Vault is sealed"));

    Health health = new VaultHealthIndicator(cipher).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "sealed");
    assertNoLeak(health, cipher);
  }

  @Test
  void down_sealed_whenAppRoleLoginIsRefusedWith503() {
    var cipher = new StubCipher();
    cipher.failure =
        new SecretBackendException(
            LEAKY_MESSAGE,
            new VaultLoginException(
                LEAKY_MESSAGE,
                new RestClientResponseException(
                    "Vault is sealed", 503, "Service Unavailable", null, null, null)));

    Health health = new VaultHealthIndicator(cipher).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "sealed");
    assertNoLeak(health, cipher);
  }

  @Test
  void down_error_forAnyOtherFailure() {
    var cipher = new StubCipher();
    cipher.failure = new IllegalStateException(LEAKY_MESSAGE);

    Health health = new VaultHealthIndicator(cipher).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "error");
    assertNoLeak(health, cipher);
  }

  @Test
  void down_error_whenRoundTripDoesNotMatch() {
    var cipher = new StubCipher();
    cipher.corrupt = true;

    Health health = new VaultHealthIndicator(cipher).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "error");
    assertNoLeak(health, cipher);
  }

  /** No detail value may carry the probe plaintext, its ciphertext, or the exception message. */
  private static void assertNoLeak(Health health, StubCipher cipher) {
    List<String> rendered = new ArrayList<>();
    health.getDetails().values().forEach(v -> rendered.add(String.valueOf(v)));
    for (String value : rendered) {
      for (String plaintext : cipher.plaintexts) {
        assertThat(value).doesNotContain(plaintext);
      }
      assertThat(value).doesNotContain(LEAKY_MESSAGE).doesNotContain("Vault is sealed");
    }
  }

  private static VaultClientResponseException response(int status, String body) {
    return new VaultClientResponseException(body) {
      @Override
      public HttpStatusCode getStatusCode() {
        return HttpStatusCode.valueOf(status);
      }

      @Override
      public String getStatusText() {
        return "";
      }

      @Override
      public byte[] getResponseBodyAsByteArray() {
        return body.getBytes();
      }

      @Override
      public String getResponseBodyAsString(Charset charset) {
        return body;
      }

      @Override
      public <E> E getResponseBodyAs(Class<E> type) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <E> E getResponseBodyAs(ParameterizedTypeReference<E> type) {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** Records every plaintext it encrypts. Fails on demand, or corrupts the decrypt result. */
  private static final class StubCipher implements SecretCipher {
    final List<String> plaintexts = new ArrayList<>();
    RuntimeException failure;
    boolean corrupt;

    @Override
    public String encrypt(String plaintext, SecretContext ctx) {
      if (failure != null) {
        throw failure;
      }
      plaintexts.add(plaintext);
      return "stub:" + plaintext;
    }

    @Override
    public String decrypt(String ciphertext, SecretContext ctx) {
      if (failure != null) {
        throw failure;
      }
      return corrupt ? "tampered" : ciphertext.substring("stub:".length());
    }

    @Override
    public List<String> decryptBatch(List<CipherItem> items) {
      throw new UnsupportedOperationException();
    }
  }
}

package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.vault.authentication.VaultLoginException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class VaultHealthIndicatorTest {

  private static final String LEAK = "connect to vault.internal:8200 refused";

  @Test
  void up_whenRoundTripSucceeds() {
    Health health = new VaultHealthIndicator(new StubCipher(null, false)).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void down_error_whenRoundTripDoesNotMatch() {
    assertDown(new StubCipher(null, true), "error");
  }

  @ParameterizedTest
  @MethodSource("failures")
  void down_withReasonFromCauseChain(RuntimeException failure, String reason) {
    assertDown(new StubCipher(failure, false), reason);
  }

  static Stream<Arguments> failures() {
    return Stream.of(
        arguments(backend(new ResourceAccessException(LEAK, new IOException())), "unreachable"),
        arguments(backend(new VaultLoginException(LEAK, response(503))), "sealed"),
        arguments(backend(new VaultLoginException(LEAK, response(403))), "error"),
        arguments(new IllegalStateException(LEAK), "error"));
  }

  private static SecretBackendException backend(Throwable cause) {
    return new SecretBackendException(LEAK, cause);
  }

  private static void assertDown(StubCipher cipher, String reason) {
    Health health = new VaultHealthIndicator(cipher).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsExactly(entry("reason", reason));
    assertThat(health.getDetails().toString())
        .doesNotContain(LEAK)
        .doesNotContain(cipher.seen.getFirst());
  }

  private static RestClientResponseException response(int status) {
    return new RestClientResponseException("vault", status, "status", null, null, null);
  }

  /** Records the probe before failing, so the leak check sees the real value. */
  private static final class StubCipher implements SecretCipher {
    final List<String> seen = new ArrayList<>();
    private final RuntimeException failure;
    private final boolean corrupt;

    StubCipher(RuntimeException failure, boolean corrupt) {
      this.failure = failure;
      this.corrupt = corrupt;
    }

    @Override
    public String encrypt(String plaintext, SecretContext ctx) {
      seen.add(plaintext);
      if (failure != null) {
        throw failure;
      }
      return plaintext;
    }

    @Override
    public String decrypt(String ciphertext, SecretContext ctx) {
      if (failure != null) {
        throw failure;
      }
      return corrupt ? "tampered" : ciphertext;
    }

    @Override
    public List<String> decryptBatch(List<CipherItem> items) {
      return List.of();
    }
  }
}

package dev.emitkit.common.secret;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.HttpStatus;
import org.springframework.vault.client.VaultClientResponseException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reports whether the {@link SecretCipher} can round-trip a value. Details carry only a reason, one
 * of {@code sealed}, {@code unreachable} or {@code error}; never the probe value, its ciphertext or
 * an exception message.
 */
public final class VaultHealthIndicator implements HealthIndicator {

  private static final String PROBE_PLAINTEXT = "emitkit-vault-health-probe";
  private static final SecretContext PROBE_CONTEXT = new SecretContext("health", "check", "0");

  private final SecretCipher cipher;

  /**
   * Creates the indicator.
   *
   * @param cipher the cipher to probe
   */
  public VaultHealthIndicator(SecretCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public Health health() {
    try {
      String ciphertext = cipher.encrypt(PROBE_PLAINTEXT, PROBE_CONTEXT);
      if (PROBE_PLAINTEXT.equals(cipher.decrypt(ciphertext, PROBE_CONTEXT))) {
        return Health.up().build();
      }
      return down("error");
    } catch (SecretBackendException e) {
      return down(isSealed(e) ? "sealed" : "unreachable");
    } catch (RuntimeException e) {
      return down("error");
    }
  }

  /**
   * Vault answers 503 while it is sealed. A Transit call reports that as a {@link
   * VaultClientResponseException}; a failed AppRole login reports it as a {@link
   * RestClientResponseException} inside a {@code VaultLoginException}, so the whole chain is
   * checked.
   */
  private static boolean isSealed(Throwable e) {
    int unavailable = HttpStatus.SERVICE_UNAVAILABLE.value();
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof VaultClientResponseException response
          && response.getStatusCode().value() == unavailable) {
        return true;
      }
      if (cause instanceof RestClientResponseException response
          && response.getStatusCode().value() == unavailable) {
        return true;
      }
    }
    return false;
  }

  private static Health down(String reason) {
    return Health.down().withDetail("reason", reason).build();
  }
}

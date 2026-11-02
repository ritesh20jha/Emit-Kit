package dev.emitkit.common.secret;

import java.io.IOException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.HttpStatus;
import org.springframework.vault.client.VaultClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Round-trips a probe through the cipher. Details carry only {@code reason}, never values. */
public final class VaultHealthIndicator implements HealthIndicator {

  private static final String PROBE = "emitkit-vault-health-probe";
  private static final SecretContext PROBE_CONTEXT = new SecretContext("health", "check", "0");

  private final SecretCipher cipher;

  public VaultHealthIndicator(SecretCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public Health health() {
    try {
      String ciphertext = cipher.encrypt(PROBE, PROBE_CONTEXT);
      if (PROBE.equals(cipher.decrypt(ciphertext, PROBE_CONTEXT))) {
        return Health.up().build();
      }
      return down("error");
    } catch (SecretBackendException e) {
      return down(reason(e));
    } catch (RuntimeException e) {
      return down("error");
    }
  }

  /** sealed: a 503 in the chain; unreachable: an I/O failure; otherwise error. */
  private static String reason(Throwable e) {
    boolean io = false;
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (isUnavailable(cause)) {
        return "sealed";
      }
      io |= cause instanceof IOException || cause instanceof ResourceAccessException;
    }
    return io ? "unreachable" : "error";
  }

  /** Transit reports 503 as the first type, a failed AppRole login as the second. */
  private static boolean isUnavailable(Throwable cause) {
    int unavailable = HttpStatus.SERVICE_UNAVAILABLE.value();
    return (cause instanceof VaultClientResponseException v
            && v.getStatusCode().value() == unavailable)
        || (cause instanceof RestClientResponseException r
            && r.getStatusCode().value() == unavailable);
  }

  private static Health down(String reason) {
    return Health.down().withDetail("reason", reason).build();
  }
}

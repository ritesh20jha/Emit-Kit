package dev.emitkit.common.secret;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import java.util.Map;

/** The secret backend is unreachable, sealed or failing. A retry may succeed. */
public class SecretBackendException extends TransientException {

  /**
   * Creates the exception.
   *
   * @param detail a human-readable detail; must not include secret values
   * @param cause the underlying failure
   */
  public SecretBackendException(String detail, Throwable cause) {
    super(ErrorCode.SECRETS_UNAVAILABLE, detail, Map.of(), cause);
  }
}

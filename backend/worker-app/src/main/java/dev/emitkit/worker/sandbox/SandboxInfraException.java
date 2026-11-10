package dev.emitkit.worker.sandbox;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import java.util.Map;

/**
 * A sandbox run failed because of the Docker daemon, a missing image or a failed copy, not because
 * of the checked code. The worker retries it.
 */
public class SandboxInfraException extends TransientException {

  /** Creates the exception with the underlying Docker or I/O failure as its cause. */
  public SandboxInfraException(String detail, Throwable cause) {
    super(ErrorCode.INTERNAL, detail, Map.of(), cause);
  }
}

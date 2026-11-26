package dev.emitkit.worker.sandbox;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import java.util.Map;

/** A Docker daemon, image or copy failure. Transient, so the worker retries it. */
public class SandboxInfraException extends TransientException {

  public SandboxInfraException(String detail, Throwable cause) {
    super(ErrorCode.INTERNAL, detail, Map.of(), cause);
  }
}

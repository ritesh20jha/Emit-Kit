package dev.emitkit.vcs.error;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;

/** The provider failed on its side, for example with a 5xx response. The worker retries these. */
public class VcsServerException extends TransientException {

  /** Creates the exception with a detail message. */
  public VcsServerException(String detail) {
    super(ErrorCode.INTERNAL, detail);
  }
}

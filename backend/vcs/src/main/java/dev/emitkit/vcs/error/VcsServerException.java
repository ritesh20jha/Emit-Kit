package dev.emitkit.vcs.error;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import java.util.Map;

/** The provider failed on its side, for example with a 5xx response. The worker retries these. */
public class VcsServerException extends TransientException {

  /** Creates the exception with a detail message. */
  public VcsServerException(String detail) {
    super(ErrorCode.INTERNAL, detail);
  }

  /** Creates the exception with a detail message and the underlying cause. */
  public VcsServerException(String detail, Throwable cause) {
    super(ErrorCode.INTERNAL, detail, Map.of(), cause);
  }
}

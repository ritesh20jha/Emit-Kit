package dev.emitkit.common.error;

import java.util.Map;

/** An {@link AppException} for failures that may succeed if retried. The worker retries these. */
public class TransientException extends AppException {

  /** Creates a transient exception with no extra properties and no cause. */
  public TransientException(ErrorCode code, String detail) {
    super(code, detail);
  }

  /**
   * Creates a transient exception. See {@link AppException#AppException(ErrorCode, String, Map,
   * Throwable)}.
   */
  public TransientException(
      ErrorCode code, String detail, Map<String, Object> extra, Throwable cause) {
    super(code, detail, extra, cause);
  }
}

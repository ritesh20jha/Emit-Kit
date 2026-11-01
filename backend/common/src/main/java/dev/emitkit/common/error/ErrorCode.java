package dev.emitkit.common.error;

/**
 * Stable application error codes. Each code carries the HTTP status that the API's
 * ProblemDetailsAdvice uses when it renders the error.
 */
public enum ErrorCode {
  SPEC_CONFLICT(409),
  SPEC_NOT_FOUND(404),
  SPEC_TOO_LARGE(413),
  SPEC_INVALID(422),
  LINT_FAILED(422),
  BREAKING_BLOCKED(422),
  GENERATOR_ERROR(500),
  CHECK_FAILED(422),
  SANDBOX_TIMEOUT(504),
  STORAGE_ERROR(503),
  VCS_CONFLICT(409),
  VCS_AUTH(401),
  VCS_RATE_LIMITED(429),
  SECRETS_UNAVAILABLE(503),
  FORBIDDEN(403),
  NOT_FOUND(404),
  VALIDATION_FAILED(400),
  CONFLICT(409),
  RATE_LIMITED(429),
  NOT_IMPLEMENTED(501),
  INTERNAL(500);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  /** The HTTP status code associated with this error. */
  public int httpStatus() {
    return httpStatus;
  }
}

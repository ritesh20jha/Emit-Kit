package dev.emitkit.vcs.error;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;

/** The provider rejected a write because the target changed, for example a stale blob sha. */
public class VcsConflictException extends AppException {

  /** Creates the exception with a detail message. */
  public VcsConflictException(String detail) {
    super(ErrorCode.VCS_CONFLICT, detail);
  }
}

package dev.emitkit.vcs.error;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;

/** The repository, branch, file or other object does not exist at the provider. */
public class VcsNotFoundException extends AppException {

  /** Creates the exception with a detail message. */
  public VcsNotFoundException(String detail) {
    super(ErrorCode.NOT_FOUND, detail);
  }
}

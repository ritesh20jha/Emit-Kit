package dev.emitkit.vcs.error;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;

/** The provider rejected the access token, or the identity lacks permission. */
public class VcsAuthException extends AppException {

  /** Creates the exception with a detail message. */
  public VcsAuthException(String detail) {
    super(ErrorCode.VCS_AUTH, detail);
  }
}

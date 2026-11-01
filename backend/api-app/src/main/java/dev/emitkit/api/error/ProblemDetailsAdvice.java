package dev.emitkit.api.error;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders errors as RFC 9457 Problem Details. {@link AppException}s keep their {@link ErrorCode}
 * and extra properties; unexpected errors are logged in full but answered with a generic detail, so
 * no internal message or stack trace reaches the client.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);
  private static final String TYPE_BASE = "https://emitkit.dev/errors/";

  /** Maps an application error to its code's HTTP status, with the extra properties. */
  @ExceptionHandler(AppException.class)
  public ResponseEntity<ProblemDetail> handleAppException(AppException ex) {
    return render(problem(ex.code(), ex.getMessage(), ex.extra()));
  }

  /** Maps bean validation failures to VALIDATION_FAILED with one entry per invalid field. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe ->
                Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
            .toList();
    ProblemDetail pd =
        problem(
            ErrorCode.VALIDATION_FAILED, "Request validation failed.", Map.of("errors", errors));
    return ResponseEntity.status(pd.getStatus()).<Object>body(pd);
  }

  /** Maps authorization failures to FORBIDDEN. */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return render(problem(ErrorCode.FORBIDDEN, "Access denied.", Map.of()));
  }

  /** Answers any other error with INTERNAL; the cause is logged, never returned. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    LOG.error("Unhandled exception", ex);
    return render(problem(ErrorCode.INTERNAL, "An unexpected error occurred.", Map.of()));
  }

  private static ProblemDetail problem(ErrorCode code, String detail, Map<String, ?> extra) {
    HttpStatus status = HttpStatus.valueOf(code.httpStatus());
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(URI.create(TYPE_BASE + code.name()));
    pd.setTitle(status.getReasonPhrase());
    // Extras first so that a clashing "code" key cannot replace the real error code.
    extra.forEach(pd::setProperty);
    pd.setProperty("code", code.name());
    return pd;
  }

  private static ResponseEntity<ProblemDetail> render(ProblemDetail pd) {
    return ResponseEntity.status(pd.getStatus()).body(pd);
  }
}

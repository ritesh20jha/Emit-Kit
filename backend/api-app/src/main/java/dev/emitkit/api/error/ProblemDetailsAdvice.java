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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

  /**
   * Maps method-parameter constraint failures (for example {@code @Min} on a request parameter) to
   * VALIDATION_FAILED, one entry per violation. Return-value failures are server errors and keep
   * the framework's 500 path.
   */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (ex.isForReturnValue()) {
      return super.handleHandlerMethodValidationException(ex, headers, status, request);
    }
    List<Map<String, String>> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(result ->
                result.getResolvableErrors().stream()
                    .map(error ->
                        Map.of(
                            "field", String.valueOf(result.getMethodParameter().getParameterName()),
                            "message", String.valueOf(error.getDefaultMessage()))))
            .toList();
    ProblemDetail pd =
        problem(
            ErrorCode.VALIDATION_FAILED, "Request validation failed.", Map.of("errors", errors));
    return ResponseEntity.status(pd.getStatus()).<Object>body(pd);
  }

  /**
   * Framework errors (no handler, wrong method, malformed body, ...) arrive here without a code.
   * The status the framework chose stays; the code and type are added from that status.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response.getBody() instanceof ProblemDetail pd
        && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
      ErrorCode code = fallbackCode(pd.getStatus());
      pd.setType(URI.create(TYPE_BASE + code.name()));
      pd.setProperty("code", code.name());
    }
    return response;
  }

  /** The code for a framework error that has no emitkit code of its own. */
  private static ErrorCode fallbackCode(int status) {
    if (status == 501) {
      return ErrorCode.NOT_IMPLEMENTED;
    }
    if (status >= 500) {
      return ErrorCode.INTERNAL;
    }
    return switch (status) {
      case 403 -> ErrorCode.FORBIDDEN;
      case 404 -> ErrorCode.NOT_FOUND;
      case 409 -> ErrorCode.CONFLICT;
      case 429 -> ErrorCode.RATE_LIMITED;
      default -> ErrorCode.VALIDATION_FAILED;
    };
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

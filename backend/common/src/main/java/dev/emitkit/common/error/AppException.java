package dev.emitkit.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base runtime exception carrying an {@link ErrorCode}. The {@link #extra()} entries are rendered
 * as extension properties of the Problem Details response.
 */
public class AppException extends RuntimeException {

  private final ErrorCode code;
  private final Map<String, Object> extra;

  /** Creates an exception with no extra properties and no cause. */
  public AppException(ErrorCode code, String detail) {
    this(code, detail, Map.of(), null);
  }

  /**
   * Creates an exception.
   *
   * @param code the error code; must not be null
   * @param detail the human-readable detail, used as the message
   * @param extra extra Problem Details properties; a null map is treated as empty. The map is
   *     copied, so later changes to the argument do not affect this exception.
   * @param cause the underlying cause, or null
   */
  public AppException(ErrorCode code, String detail, Map<String, Object> extra, Throwable cause) {
    super(detail, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.extra = extra == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
  }

  /** The error code of this exception. */
  public ErrorCode code() {
    return code;
  }

  /** Unmodifiable extra Problem Details properties; never null. */
  public Map<String, Object> extra() {
    return extra;
  }
}

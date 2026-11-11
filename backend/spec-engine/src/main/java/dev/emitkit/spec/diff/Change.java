package dev.emitkit.spec.diff;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * One change within an operation. {@code location} is e.g. {@code "query.limit"} or {@code "body"}.
 */
public record Change(
    @Schema(requiredMode = REQUIRED) ChangeKind kind,
    @Schema(requiredMode = REQUIRED) String location,
    @Schema(requiredMode = REQUIRED) boolean breaking) {

  public Change {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(location, "location");
  }
}

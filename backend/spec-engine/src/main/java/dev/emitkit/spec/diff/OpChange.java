package dev.emitkit.spec.diff;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/** An operation present on both sides whose contract changed. */
public record OpChange(
    @Schema(requiredMode = REQUIRED) OpRef op,
    @Schema(requiredMode = REQUIRED) List<Change> changes) {

  public OpChange {
    Objects.requireNonNull(op, "op");
    changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
  }
}

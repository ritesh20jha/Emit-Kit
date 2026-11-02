package dev.emitkit.spec.parse;

import dev.emitkit.spec.model.ApiModel;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Outcome of parsing an OpenAPI document. {@code format} is {@code "yaml"} or {@code "json"}. */
public record ParseResult(
    @Nullable ApiModel model,
    List<String> errors,
    @Nullable String infoVersion,
    String contentHash,
    String format) {

  public ParseResult {
    errors = List.copyOf(errors);
  }

  public boolean ok() {
    return model != null && errors.isEmpty();
  }
}

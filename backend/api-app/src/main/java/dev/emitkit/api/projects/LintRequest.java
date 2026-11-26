package dev.emitkit.api.projects;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A spec to lint, as text. The limit is 2 MiB. */
public record LintRequest(@NotNull @Size(max = 2_097_152) String content) {}

package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * A spec save. {@code sha} is the spec's sha when it was read; a different current sha gives a
 * conflict. {@code generate} also starts a generation run.
 */
public record SaveSpecRequest(
    @NotNull String content,
    @NotBlank String sha,
    @Schema(types = {"string", "null"}) @Nullable String message,
    @Schema(requiredMode = REQUIRED) boolean generate) {}

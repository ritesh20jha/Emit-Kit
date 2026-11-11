package dev.emitkit.api.projects;

import dev.emitkit.domain.BreakingGate;
import dev.emitkit.domain.LintFailOn;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.VersionStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The generation settings of a project. {@code aiApiKey} is write-only: null keeps the stored key
 * and an empty string clears it.
 */
public record ConfigRequest(
    @NotEmpty List<OutputKind> outputs,
    @Schema(types = {"array", "null"}) @Nullable List<String> sdkLanguages,
    @NotBlank String outputDir,
    @Schema(types = {"string", "null"}) @Nullable String npmScope,
    @Schema(types = {"string", "null"}) @Nullable String pypiName,
    @Schema(types = {"string", "null"}) @Nullable String mavenGroupId,
    @Schema(types = {"string", "null"}) @Nullable String mavenArtifactId,
    @NotNull VersionStrategy versionStrategy,
    @NotNull BreakingGate breakingGate,
    @NotNull LintFailOn lintFailOn,
    @Schema(types = {"string", "null"}) @Nullable String aiProvider,
    @Schema(types = {"string", "null"}) @Nullable String aiModel,
    @Schema(types = {"string", "null"}) @Nullable String aiApiKey) {}

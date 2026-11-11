package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.BreakingGate;
import dev.emitkit.domain.LintFailOn;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.VersionStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The stored generation settings. It is {@link ConfigRequest} without the API key: {@code aiKeySet}
 * says whether a key is stored. {@code version} rises with every saved change.
 */
public record ConfigResponse(
    @Schema(requiredMode = REQUIRED) int version,
    @Schema(requiredMode = REQUIRED) List<OutputKind> outputs,
    @Schema(
            requiredMode = REQUIRED,
            types = {"array", "null"})
        @Nullable List<String> sdkLanguages,
    @Schema(requiredMode = REQUIRED) String outputDir,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String npmScope,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String pypiName,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String mavenGroupId,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String mavenArtifactId,
    @Schema(requiredMode = REQUIRED) VersionStrategy versionStrategy,
    @Schema(requiredMode = REQUIRED) BreakingGate breakingGate,
    @Schema(requiredMode = REQUIRED) LintFailOn lintFailOn,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String aiProvider,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String aiModel,
    @Schema(requiredMode = REQUIRED) boolean aiKeySet) {}

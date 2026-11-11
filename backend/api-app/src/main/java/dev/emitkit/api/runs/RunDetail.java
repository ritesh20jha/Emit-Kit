package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.ChangeType;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.StepName;
import dev.emitkit.spec.diff.SpecDiff;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One run in full, with its steps, lint counts and approval.
 *
 * @param artifactAvailable true while the run's zip can be downloaded; artifacts expire after 30
 *     days, and a failed run never has one
 */
public record RunDetail(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String projectId,
    @Schema(requiredMode = REQUIRED) RunStatus status,
    @Schema(requiredMode = REQUIRED) RunTrigger trigger,
    @Schema(requiredMode = REQUIRED) boolean dryRun,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable StepName currentStep,
    @Schema(requiredMode = REQUIRED) List<StepState> steps,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String failureCode,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String failureMessage,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String commitSha,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String sdkVersion,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable ChangeType changeType,
    @Schema(
            requiredMode = REQUIRED,
            types = {"object", "null"})
        @Nullable SpecDiff diffSummary,
    @Schema(requiredMode = REQUIRED) LintCounts lint,
    @Schema(
            requiredMode = REQUIRED,
            types = {"object", "null"})
        @Nullable ApprovalView approval,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String branchName,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String prUrl,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String parentRunId,
    @Schema(requiredMode = REQUIRED) Instant createdAt,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable Instant startedAt,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable Instant finishedAt,
    @Schema(requiredMode = REQUIRED) boolean artifactAvailable) {}

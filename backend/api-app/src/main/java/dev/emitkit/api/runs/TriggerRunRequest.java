package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** Starts a run. A dry run generates and checks the output without committing it. */
public record TriggerRunRequest(@Schema(requiredMode = REQUIRED) boolean dryRun) {}

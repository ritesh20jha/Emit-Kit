package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Whether the repository's push webhook is registered, and its id on GitHub when it is. */
public record WebhookStatus(
    @Schema(requiredMode = REQUIRED) boolean registered,
    @Schema(
            requiredMode = REQUIRED,
            types = {"integer", "null"})
        @Nullable Long webhookId) {}

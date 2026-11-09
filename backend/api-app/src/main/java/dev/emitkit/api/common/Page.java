package dev.emitkit.api.common;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a cursor-paginated list.
 *
 * @param nextCursor the cursor for the next page, or null on the last page
 */
public record Page<T>(
    @Schema(requiredMode = REQUIRED) List<T> items,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String nextCursor) {}

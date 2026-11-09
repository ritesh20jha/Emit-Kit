package dev.emitkit.api.common;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One page of a cursor-paginated list. {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, @Nullable String nextCursor) {}

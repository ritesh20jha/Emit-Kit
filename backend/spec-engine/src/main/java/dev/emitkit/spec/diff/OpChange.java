package dev.emitkit.spec.diff;

import java.util.List;

/** The changes made to one operation that exists in both versions of the spec. */
public record OpChange(OpRef op, List<Change> changes) {}

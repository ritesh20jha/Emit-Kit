package dev.emitkit.spec.model;

import org.jspecify.annotations.Nullable;

/** A schema node. Sealed: the generators switch exhaustively over these seven variants. */
public sealed interface Schema
    permits ObjectSchema,
        ArraySchema,
        PrimitiveSchema,
        EnumSchema,
        RefSchema,
        OneOfSchema,
        AllOfSchema {

  boolean nullable();

  @Nullable String description();
}

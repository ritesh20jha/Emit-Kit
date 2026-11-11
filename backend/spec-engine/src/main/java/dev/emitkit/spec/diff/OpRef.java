package dev.emitkit.spec.diff;

/** Identifies one operation: its operationId and the method and path it is served on. */
public record OpRef(String operationId, String method, String path) {}

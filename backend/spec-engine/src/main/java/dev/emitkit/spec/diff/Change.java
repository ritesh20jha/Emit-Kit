package dev.emitkit.spec.diff;

/**
 * One change inside an operation.
 *
 * @param location where the change is, e.g. {@code query.limit}, {@code body.owner.name} or {@code
 *     200.items[].id}
 * @param breaking true when existing clients can stop working because of the change
 */
public record Change(ChangeKind kind, String location, boolean breaking) {}

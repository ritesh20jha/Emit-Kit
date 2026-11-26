package dev.emitkit.domain;

/** How many lint findings of one run have a given severity. Built by a JPQL constructor query. */
public record SeverityCount(Severity severity, long count) {}

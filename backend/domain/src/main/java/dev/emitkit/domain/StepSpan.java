package dev.emitkit.domain;

import java.time.Instant;

/** When a step of a run first and last wrote a log line. Built by a JPQL constructor query. */
public record StepSpan(StepName step, Instant first, Instant last) {}

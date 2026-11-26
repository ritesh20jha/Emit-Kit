package dev.emitkit.vcs;

import java.time.Instant;

/** A repository. {@code fullName} is {@code owner/name}. */
public record VcsRepo(
    String fullName, String defaultBranch, boolean isPrivate, Instant pushedAt, String owner) {}

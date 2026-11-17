package dev.emitkit.worker.steps;

import org.springframework.lang.Nullable;

/**
 * What FETCH_SPEC saves: the commit the spec was read at, and its content. PARSE_SPEC drops the
 * content once the snapshot is stored, so a run that finished parsing keeps only the commit sha.
 */
public record FetchOutput(String commitSha, @Nullable String content) {}

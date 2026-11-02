package dev.emitkit.vcs;

/** A file to write in a commit. Kept free of generator types so vcs depends only on common. */
public record CommitFile(String path, String content) {}

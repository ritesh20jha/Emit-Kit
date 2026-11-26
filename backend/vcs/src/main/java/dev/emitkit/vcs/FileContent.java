package dev.emitkit.vcs;

/** A file's content and its git blob sha. */
public record FileContent(String content, String sha) {}

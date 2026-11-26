package dev.emitkit.vcs;

/** An opened pull request. */
public record PullRequest(int number, String htmlUrl, String headRef) {}

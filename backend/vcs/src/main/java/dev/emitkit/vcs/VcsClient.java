package dev.emitkit.vcs;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operations on repositories of one version-control provider, acting as one identity. */
public interface VcsClient {

  /** Organizations the identity belongs to. */
  List<VcsOrg> listOrgs();

  /** Repositories visible to the identity. */
  List<VcsRepo> listRepos();

  /** Creates a repository in {@code org}. */
  VcsRepo createRepo(String org, String name, boolean isPrivate);

  /** Reads a file at {@code ref} (branch, tag or commit sha). Its sha is the git blob sha. */
  FileContent getFile(String repo, String path, String ref);

  /**
   * Writes a file on the default branch. {@code expectedSha} is the blob sha the write replaces; it
   * must match the current blob sha or the write fails with a conflict.
   */
  CommitRef putFile(String repo, String path, String content, String expectedSha, String message);

  /** Creates {@code name} pointing at the commit {@code fromSha}. */
  void createBranch(String repo, String name, String fromSha);

  /** Deletes a branch. */
  void deleteBranch(String repo, String name);

  /**
   * Maps each path under {@code pathPrefix} at {@code ref} to its git blob sha, in one tree call.
   */
  Map<String, String> listBlobShas(String repo, String ref, String pathPrefix);

  /** Commits {@code files} on top of {@code branch} in one commit and moves the branch to it. */
  CommitRef commitTree(String repo, String branch, List<CommitFile> files, String message);

  /** Opens a pull request from {@code head} into {@code base}. */
  PullRequest openPr(String repo, String head, String base, String title, String body);

  /** Adds a comment to a pull request. */
  void comment(String repo, int prNumber, String body);

  /** Creates {@code tag} at {@code sha}. Returns false if the tag already existed. */
  boolean createTag(String repo, String tag, String sha);

  /** Registers a webhook for the given events. */
  Webhook registerWebhook(String repo, String url, String secret, Set<String> events);

  /** Removes a webhook. */
  void deleteWebhook(String repo, long id);

  /** The identity's current API quota. */
  RateLimit rateLimit();
}

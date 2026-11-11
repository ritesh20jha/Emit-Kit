package dev.emitkit.vcs.ratelimit;

import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.CommitRef;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.PullRequest;
import dev.emitkit.vcs.RateLimit;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.VcsRepo;
import dev.emitkit.vcs.Webhook;
import dev.emitkit.vcs.error.VcsRateLimitException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link VcsClient} that checks the stored quota of its user before every call, except {@link
 * #rateLimit()}, and applies its {@link RateLimitPolicy} when the quota is low. A stored quota
 * counts as low when fewer than 100 requests are left and its reset is still in the future;
 * otherwise the call goes straight through.
 */
public final class RateLimitedVcsClient implements VcsClient {

  /** Remaining requests below which the quota counts as low. */
  private static final int LOW_QUOTA = 100;

  /** The longest wait a background call accepts before a reset. */
  private static final Duration MAX_BACKGROUND_WAIT = Duration.ofMinutes(5);

  private final VcsClient delegate;
  private final String userId;
  private final RateLimitStore store;
  private final RateLimitPolicy policy;
  private final Clock clock;
  private final Sleeper sleeper;

  /** Creates the decorator for {@code userId}, whose quota is read from {@code store}. */
  public RateLimitedVcsClient(
      VcsClient delegate,
      String userId,
      RateLimitStore store,
      RateLimitPolicy policy,
      Clock clock,
      Sleeper sleeper) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.store = Objects.requireNonNull(store, "store");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  @Override
  public List<VcsOrg> listOrgs() {
    guard();
    return delegate.listOrgs();
  }

  @Override
  public List<VcsRepo> listRepos() {
    guard();
    return delegate.listRepos();
  }

  @Override
  public VcsRepo createRepo(String org, String name, boolean isPrivate) {
    guard();
    return delegate.createRepo(org, name, isPrivate);
  }

  @Override
  public FileContent getFile(String repo, String path, String ref) {
    guard();
    return delegate.getFile(repo, path, ref);
  }

  @Override
  public CommitRef putFile(
      String repo, String path, String content, String expectedSha, String message) {
    guard();
    return delegate.putFile(repo, path, content, expectedSha, message);
  }

  @Override
  public void createBranch(String repo, String name, String fromSha) {
    guard();
    delegate.createBranch(repo, name, fromSha);
  }

  @Override
  public String branchHead(String repo, String branch) {
    guard();
    return delegate.branchHead(repo, branch);
  }

  @Override
  public void deleteBranch(String repo, String name) {
    guard();
    delegate.deleteBranch(repo, name);
  }

  @Override
  public Map<String, String> listBlobShas(String repo, String ref, String pathPrefix) {
    guard();
    return delegate.listBlobShas(repo, ref, pathPrefix);
  }

  @Override
  public CommitRef commitTree(String repo, String branch, List<CommitFile> files, String message) {
    guard();
    return delegate.commitTree(repo, branch, files, message);
  }

  @Override
  public PullRequest openPr(String repo, String head, String base, String title, String body) {
    guard();
    return delegate.openPr(repo, head, base, title, body);
  }

  @Override
  public void comment(String repo, int prNumber, String body) {
    guard();
    delegate.comment(repo, prNumber, body);
  }

  @Override
  public boolean createTag(String repo, String tag, String sha) {
    guard();
    return delegate.createTag(repo, tag, sha);
  }

  @Override
  public Webhook registerWebhook(String repo, String url, String secret, Set<String> events) {
    guard();
    return delegate.registerWebhook(repo, url, secret, events);
  }

  @Override
  public void deleteWebhook(String repo, long id) {
    guard();
    delegate.deleteWebhook(repo, id);
  }

  /** Not guarded: reading the quota must work when the quota is exhausted. */
  @Override
  public RateLimit rateLimit() {
    return delegate.rateLimit();
  }

  /** Applies the policy if the stored quota is low; returns normally when the call may proceed. */
  private void guard() {
    Optional<RateLimit> stored = store.get(userId);
    if (stored.isEmpty() || stored.get().remaining() >= LOW_QUOTA) {
      return;
    }
    RateLimit quota = stored.get();
    Instant now = clock.instant();
    if (!quota.resetAt().isAfter(now)) {
      return;
    }
    Duration untilReset = Duration.between(now, quota.resetAt());
    if (policy == RateLimitPolicy.BACKGROUND && untilReset.compareTo(MAX_BACKGROUND_WAIT) <= 0) {
      sleeper.sleep(untilReset);
      return;
    }
    throw new VcsRateLimitException(
        "VCS API quota low: " + quota.remaining() + " requests left", quota.resetAt());
  }
}

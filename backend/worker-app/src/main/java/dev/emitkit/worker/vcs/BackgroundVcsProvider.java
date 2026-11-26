package dev.emitkit.worker.vcs;

import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.VcsProviderId;
import dev.emitkit.vcs.WebhookVerifier;
import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import java.util.Objects;

/**
 * Gives the clients that the pipeline asks for by project the {@link RateLimitPolicy#BACKGROUND}
 * policy. No user is waiting on a run, so a low quota is waited out rather than failed. Every other
 * method passes straight through.
 */
final class BackgroundVcsProvider implements VcsProvider {

  private final VcsProvider delegate;

  BackgroundVcsProvider(VcsProvider delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public VcsProviderId id() {
    return delegate.id();
  }

  @Override
  public VcsClient forUser(String accessToken) {
    return delegate.forUser(accessToken);
  }

  @Override
  public VcsClient forProject(String creatorUserId) {
    return forProject(creatorUserId, RateLimitPolicy.BACKGROUND);
  }

  @Override
  public VcsClient forUser(String accessToken, String userId, RateLimitPolicy policy) {
    return delegate.forUser(accessToken, userId, policy);
  }

  @Override
  public VcsClient forProject(String creatorUserId, RateLimitPolicy policy) {
    return delegate.forProject(creatorUserId, policy);
  }

  @Override
  public WebhookVerifier webhookVerifier() {
    return delegate.webhookVerifier();
  }
}

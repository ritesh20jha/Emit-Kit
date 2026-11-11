package dev.emitkit.vcs;

import dev.emitkit.vcs.ratelimit.RateLimitPolicy;

/** Entry point to a version-control provider. Creates clients bound to a user or a project. */
public interface VcsProvider {

  /** The provider this instance talks to. */
  VcsProviderId id();

  /** A client that acts with the given user's access token. */
  VcsClient forUser(String accessToken);

  /** A client that acts with the token of the project's creator. */
  VcsClient forProject(String creatorUserId);

  /**
   * A client that acts with the given user's access token, guarded by {@code policy} against the
   * quota stored for {@code userId}.
   */
  VcsClient forUser(String accessToken, String userId, RateLimitPolicy policy);

  /**
   * A client that acts with the token of the project's creator, guarded by {@code policy} against
   * the quota stored for the creator.
   */
  VcsClient forProject(String creatorUserId, RateLimitPolicy policy);

  /** Verifies the signatures of incoming webhooks from this provider. */
  WebhookVerifier webhookVerifier();
}

package dev.emitkit.vcs;

/** Entry point to a version-control provider. Creates clients bound to a user or a project. */
public interface VcsProvider {

  /** The provider this instance talks to. */
  VcsProviderId id();

  /** A client that acts with the given user's access token. */
  VcsClient forUser(String accessToken);

  /** A client that acts with the token of the project's creator. */
  VcsClient forProject(String creatorUserId);

  /** Verifies the signatures of incoming webhooks from this provider. */
  WebhookVerifier webhookVerifier();
}

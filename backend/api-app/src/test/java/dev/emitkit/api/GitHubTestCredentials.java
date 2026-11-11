package dev.emitkit.api;

/**
 * GitHub client credentials for tests that load the security chain, which requires a registration.
 * Production and dev read them from the environment instead.
 */
public final class GitHubTestCredentials {

  public static final String CLIENT_ID =
      "spring.security.oauth2.client.registration.github.client-id=test-client-id";
  public static final String CLIENT_SECRET =
      "spring.security.oauth2.client.registration.github.client-secret=test-client-secret";

  private GitHubTestCredentials() {}
}

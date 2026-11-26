package dev.emitkit.vcs;

/** Looks up the provider access token of a user. Implemented in api and worker (AUTH-01). */
public interface TokenSource {

  /** The access token of {@code userId}. */
  String accessTokenFor(String userId);
}

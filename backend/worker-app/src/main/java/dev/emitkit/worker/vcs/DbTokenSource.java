package dev.emitkit.worker.vcs;

import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.OAuthAccount;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.TokenSource;
import dev.emitkit.vcs.error.VcsAuthException;

/**
 * Reads a user's GitHub token from {@code oauth_accounts}, decrypting it on each call. Mirrors the
 * api-app's source, which the worker cannot depend on.
 */
final class DbTokenSource implements TokenSource {

  /** The provider key that api-app stores GitHub accounts under. */
  static final String GITHUB = "github";

  private final UserRepository users;
  private final OAuthAccountRepository accounts;
  private final SecretCipher cipher;

  DbTokenSource(UserRepository users, OAuthAccountRepository accounts, SecretCipher cipher) {
    this.users = users;
    this.accounts = accounts;
    this.cipher = cipher;
  }

  @Override
  public String accessTokenFor(String userId) {
    User user = users.findById(userId).orElseThrow(() -> new VcsAuthException("no user " + userId));
    OAuthAccount account =
        accounts
            .findByProviderAndProviderAccountId(GITHUB, String.valueOf(user.getGithubId()))
            .orElseThrow(() -> new VcsAuthException("no GitHub account linked to " + userId));
    return cipher.decrypt(
        account.getAccessTokenEnc(),
        new SecretContext("oauth_accounts", "access_token_enc", account.getId()));
  }
}

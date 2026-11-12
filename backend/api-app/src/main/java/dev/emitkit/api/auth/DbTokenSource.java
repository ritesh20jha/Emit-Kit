package dev.emitkit.api.auth;

import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.OAuthAccount;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.TokenSource;
import dev.emitkit.vcs.error.VcsAuthException;
import org.springframework.stereotype.Component;

/** Reads a user's GitHub token from {@code oauth_accounts}, decrypting it on each call. */
@Component
public class DbTokenSource implements TokenSource {

  private final UserRepository users;
  private final OAuthAccountRepository accounts;
  private final SecretCipher cipher;

  public DbTokenSource(UserRepository users, OAuthAccountRepository accounts, SecretCipher cipher) {
    this.users = users;
    this.accounts = accounts;
    this.cipher = cipher;
  }

  @Override
  public String accessTokenFor(String userId) {
    User user = users.findById(userId).orElseThrow(() -> new VcsAuthException("no user " + userId));
    OAuthAccount account =
        accounts
            .findByProviderAndProviderAccountId(
                GitHubUserService.PROVIDER, String.valueOf(user.getGithubId()))
            .orElseThrow(() -> new VcsAuthException("no GitHub account linked to " + userId));
    return cipher.decrypt(
        account.getAccessTokenEnc(),
        new SecretContext("oauth_accounts", "access_token_enc", account.getId()));
  }
}

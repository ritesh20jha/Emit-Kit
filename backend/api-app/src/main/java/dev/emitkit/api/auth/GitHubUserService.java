package dev.emitkit.api.auth;

import dev.emitkit.common.id.Ids;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.OAuthAccount;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the GitHub profile at sign-in, upserts the user and their linked account, and stores the
 * token encrypted. The principal returned is an {@link EmitKitUser}.
 */
@Component
public class GitHubUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  static final String PROVIDER = "github";

  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> profiles =
      new DefaultOAuth2UserService();
  private final UserRepository users;
  private final OAuthAccountRepository accounts;
  private final SecretCipher cipher;

  public GitHubUserService(
      UserRepository users, OAuthAccountRepository accounts, SecretCipher cipher) {
    this.users = users;
    this.accounts = accounts;
    this.cipher = cipher;
  }

  @Override
  @Transactional
  public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
    OAuth2User profile = profiles.loadUser(request);
    long githubId = ((Number) profile.getAttribute("id")).longValue();
    String login = profile.getAttribute("login");
    String name = profile.getAttribute("name");
    String email = profile.getAttribute("email");
    String avatarUrl = profile.getAttribute("avatar_url");

    User user = users.findByGithubId(githubId).orElse(null);
    if (user == null) {
      user = new User(null, githubId, login, name, email, avatarUrl);
    } else {
      user.updateProfile(login, name, email, avatarUrl);
    }
    users.save(user);

    String providerAccountId = String.valueOf(githubId);
    OAuthAccount account =
        accounts.findByProviderAndProviderAccountId(PROVIDER, providerAccountId).orElse(null);
    // The row id is fixed before encryption: the ciphertext is bound to it.
    String accountId = account != null ? account.getId() : Ids.newUlid();
    String token = request.getAccessToken().getTokenValue();
    String scopes = String.join(",", request.getAccessToken().getScopes());
    String encrypted =
        cipher.encrypt(token, new SecretContext("oauth_accounts", "access_token_enc", accountId));
    if (account == null) {
      accounts.save(
          new OAuthAccount(
              accountId, user.getId(), PROVIDER, providerAccountId, encrypted, scopes));
    } else {
      account.rotateToken(encrypted, scopes);
      accounts.save(account);
    }

    return new EmitKitUser(user.getId(), login);
  }
}

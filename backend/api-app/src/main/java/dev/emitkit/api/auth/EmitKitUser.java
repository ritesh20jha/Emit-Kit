package dev.emitkit.api.auth;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The signed-in user, stored in the session as the OAuth2 principal. It carries no GitHub token:
 * the token lives encrypted in {@code oauth_accounts} and is read by {@link DbTokenSource}.
 *
 * @param userId the ULID of the {@code users} row
 * @param login the GitHub login at the time of sign-in
 */
public record EmitKitUser(String userId, String login) implements OAuth2User, Serializable {

  @Override
  public String getName() {
    return userId;
  }

  @Override
  public Map<String, Object> getAttributes() {
    return Map.of();
  }

  @Override
  public List<GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }
}

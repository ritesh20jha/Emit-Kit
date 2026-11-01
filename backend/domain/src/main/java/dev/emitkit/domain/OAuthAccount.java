package dev.emitkit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A user's linked external account and its access token. {@code accessTokenEnc} holds Vault Transit
 * ciphertext; encryption is applied by the caller. Maps to {@code oauth_accounts}.
 */
@Entity
@Table(name = "oauth_accounts")
public class OAuthAccount extends BaseEntity {

  @Column(name = "user_id", nullable = false, length = 26)
  private String userId;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "provider_account_id", nullable = false, length = 255)
  private String providerAccountId;

  @Column(name = "access_token_enc", nullable = false)
  private String accessTokenEnc;

  @Column(name = "scopes", nullable = false)
  private String scopes;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OAuthAccount() {}

  public OAuthAccount(
      String userId,
      String provider,
      String providerAccountId,
      String accessTokenEnc,
      String scopes) {
    this.userId = userId;
    this.provider = provider;
    this.providerAccountId = providerAccountId;
    this.accessTokenEnc = accessTokenEnc;
    this.scopes = scopes;
  }

  public String getUserId() {
    return userId;
  }

  public String getProvider() {
    return provider;
  }

  public String getProviderAccountId() {
    return providerAccountId;
  }

  public String getAccessTokenEnc() {
    return accessTokenEnc;
  }

  public String getScopes() {
    return scopes;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

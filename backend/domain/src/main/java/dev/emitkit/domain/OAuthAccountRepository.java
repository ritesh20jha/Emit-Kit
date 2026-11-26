package dev.emitkit.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Linked external accounts, keyed by ULID. */
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, String> {

  Optional<OAuthAccount> findByProviderAndProviderAccountId(
      String provider, String providerAccountId);
}

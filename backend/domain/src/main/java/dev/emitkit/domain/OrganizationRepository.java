package dev.emitkit.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Organizations, keyed by ULID. */
public interface OrganizationRepository extends JpaRepository<Organization, String> {

  Optional<Organization> findByVcsProviderAndVcsOrgId(String vcsProvider, String vcsOrgId);
}

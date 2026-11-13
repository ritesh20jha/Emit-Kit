package dev.emitkit.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Organizations, keyed by ULID. */
public interface OrganizationRepository extends JpaRepository<Organization, String> {

  Optional<Organization> findByVcsProviderAndVcsOrgId(String vcsProvider, String vcsOrgId);

  /**
   * Loads the organization with a row lock held until the transaction ends. Callers that check and
   * then change an org's admins take this lock first, so concurrent changes run one at a time.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from Organization o where o.id = :id")
  Optional<Organization> findByIdForUpdate(@Param("id") String id);
}

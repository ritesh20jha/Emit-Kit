package dev.emitkit.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Projects, keyed by ULID. */
public interface ProjectRepository extends JpaRepository<Project, String> {

  List<Project> findByOrgId(String orgId);

  /** Matches across organizations: the same repository can be connected in several of them. */
  List<Project> findByRepoFullName(String repoFullName);

  /**
   * Matches a repository as a project's source or as its separate output repository. A webhook
   * delivery passes its repository name for both.
   */
  List<Project> findByRepoFullNameOrOutputRepoFullName(
      String repoFullName, String outputRepoFullName);

  boolean existsByOrgIdAndRepoFullName(String orgId, String repoFullName);

  /**
   * Forgets the project's webhook, but only while it is still {@code webhookId}. A concurrent
   * re-register may have stored a newer webhook in the meantime, and that one must stay.
   *
   * @return the number of rows changed: 1 when the webhook was cleared, 0 otherwise
   */
  @Modifying
  @Query(
      "update Project p set p.webhookId = null, p.webhookSecretEnc = null"
          + " where p.id = :id and p.webhookId = :webhookId")
  int clearWebhookIfStill(@Param("id") String id, @Param("webhookId") long webhookId);
}

package dev.emitkit.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Projects, keyed by ULID. */
public interface ProjectRepository extends JpaRepository<Project, String> {

  List<Project> findByOrgId(String orgId);

  /** Matches across organizations: the same repository can be connected in several of them. */
  List<Project> findByRepoFullName(String repoFullName);

  boolean existsByOrgIdAndRepoFullName(String orgId, String repoFullName);
}

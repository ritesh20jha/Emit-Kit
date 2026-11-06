package dev.emitkit.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only project configs, keyed by ULID. */
public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, String> {

  Optional<ProjectConfig> findFirstByProjectIdOrderByVersionDesc(String projectId);

  /** The newest config of a project (highest version), or empty when it has none. */
  default Optional<ProjectConfig> findLatest(String projectId) {
    return findFirstByProjectIdOrderByVersionDesc(projectId);
  }

  /** The version the next config of a project should take: 1 for a new project. */
  @Query(
      "select coalesce(max(c.version), 0) + 1 from ProjectConfig c where c.projectId = :projectId")
  int nextVersion(@Param("projectId") String projectId);
}

package dev.emitkit.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Released SDK versions, keyed by ULID. */
public interface SdkVersionRepository extends JpaRepository<SdkVersion, String> {

  /** A project's released versions, newest first. */
  List<SdkVersion> findByProjectIdOrderByCreatedAtDesc(String projectId);

  /** A project's newest released version, if it has released one. */
  Optional<SdkVersion> findFirstByProjectIdOrderByCreatedAtDescIdDesc(String projectId);

  /** Whether a project has already released this version. */
  boolean existsByProjectIdAndVersion(String projectId, String version);
}

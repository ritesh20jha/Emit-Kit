package dev.emitkit.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Released SDK versions, keyed by ULID. */
public interface SdkVersionRepository extends JpaRepository<SdkVersion, String> {

  /** A project's released versions, newest first. */
  List<SdkVersion> findByProjectIdOrderByCreatedAtDesc(String projectId);

  /** Whether a project has already released this version. */
  boolean existsByProjectIdAndVersion(String projectId, String version);
}

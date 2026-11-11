package dev.emitkit.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spec snapshots, keyed by ULID. */
public interface SpecSnapshotRepository extends JpaRepository<SpecSnapshot, String> {

  /** The snapshot of a project with this content hash, if one was stored before. */
  Optional<SpecSnapshot> findByProjectIdAndContentHash(String projectId, String contentHash);

  List<SpecSnapshot> findByProjectIdOrderByCreatedAtDesc(String projectId);
}

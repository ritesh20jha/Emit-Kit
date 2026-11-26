package dev.emitkit.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Run artifacts, keyed by ULID. */
public interface RunArtifactRepository extends JpaRepository<RunArtifact, String> {

  /** The artifact a run produced, if it produced one. */
  Optional<RunArtifact> findByRunId(String runId);

  /** Artifacts created before {@code cutoff}, for the 30-day retention job. */
  List<RunArtifact> findByCreatedAtBefore(Instant cutoff);

  /** Up to 100 artifacts created before {@code cutoff}, so retention can delete them in batches. */
  List<RunArtifact> findTop100ByCreatedAtBefore(Instant cutoff);
}

package dev.emitkit.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Run approvals, keyed by ULID. */
public interface RunApprovalRepository extends JpaRepository<RunApproval, String> {

  /** The approval gate of a run, if the run has one. */
  Optional<RunApproval> findByRunId(String runId);
}

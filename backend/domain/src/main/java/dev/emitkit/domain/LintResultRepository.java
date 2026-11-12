package dev.emitkit.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lint findings, keyed by ULID. */
public interface LintResultRepository extends JpaRepository<LintResult, String> {

  /** The findings of a run. */
  List<LintResult> findByRunId(String runId);

  /** How many findings a run has at each severity. A severity with no findings has no entry. */
  @Query(
      "select new dev.emitkit.domain.SeverityCount(l.severity, count(l))"
          + " from LintResult l where l.runId = :runId group by l.severity")
  List<SeverityCount> countBySeverity(@Param("runId") String runId);
}

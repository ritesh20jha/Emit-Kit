package dev.emitkit.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Generation run logs, keyed by database sequence. */
public interface GenerationRunLogRepository extends JpaRepository<GenerationRunLog, Long> {

  /** Lines of a run after {@code seq}, in order. Pass 0 to read from the start. */
  List<GenerationRunLog> findByRunIdAndSeqGreaterThanOrderBySeq(String runId, int seq);

  /** The highest {@code seq} written for a run, or 0 if it has no lines yet. */
  @Query("select coalesce(max(l.seq), 0) from GenerationRunLog l where l.runId = :runId")
  int findMaxSeqByRunId(@Param("runId") String runId);

  /**
   * The first and last log time of each step of a run. A step with no log lines has no entry.
   * Run-level lines, which have no step, are left out.
   */
  @Query(
      "select new dev.emitkit.domain.StepSpan(l.step, min(l.ts), max(l.ts))"
          + " from GenerationRunLog l where l.runId = :runId and l.step is not null"
          + " group by l.step")
  List<StepSpan> findStepSpans(@Param("runId") String runId);

  /** Retention: deletes every log line written before {@code cutoff}. Returns the count. */
  @Transactional
  @Modifying
  @Query("delete from GenerationRunLog l where l.ts < :cutoff")
  int deleteByTsBefore(@Param("cutoff") Instant cutoff);
}

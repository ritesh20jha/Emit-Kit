package dev.emitkit.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Generation run logs, keyed by database sequence. */
public interface GenerationRunLogRepository extends JpaRepository<GenerationRunLog, Long> {

  /** Lines of a run after {@code seq}, in order. Pass 0 to read from the start. */
  List<GenerationRunLog> findByRunIdAndSeqGreaterThanOrderBySeq(String runId, int seq);

  /** Retention: deletes every log line written before {@code cutoff}. Returns the count. */
  @Modifying
  @Query("delete from GenerationRunLog l where l.ts < :cutoff")
  int deleteByTsBefore(@Param("cutoff") Instant cutoff);
}

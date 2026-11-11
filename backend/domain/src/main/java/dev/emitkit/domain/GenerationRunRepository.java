package dev.emitkit.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Generation runs, keyed by ULID. */
public interface GenerationRunRepository extends JpaRepository<GenerationRun, String> {

  /**
   * The newest run that succeeded and was not a dry run. The next diff is taken against its spec
   * snapshot (spec v1 §5.1, step 4).
   */
  default Optional<GenerationRun> findLastSuccessfulNonDryRun(String projectId) {
    return findFirstByProjectIdAndStatusAndDryRunFalseOrderByCreatedAtDescIdDesc(
        projectId, RunStatus.SUCCESS);
  }

  /**
   * One page of a project's runs, newest first.
   *
   * @param cursor the token from {@link RunCursor#encode} for the last run of the previous page, or
   *     {@code null} for the first page
   * @param limit the maximum number of runs to return
   * @return at most {@code limit} runs, empty when there are no more
   */
  default List<GenerationRun> findByProjectId(String projectId, String cursor, int limit) {
    Pageable page = PageRequest.of(0, limit);
    if (cursor == null) {
      return findFirstPageByProjectId(projectId, page);
    }
    RunCursor after = RunCursor.decode(cursor);
    return findPageAfter(projectId, after.createdAt(), after.id(), page);
  }

  Optional<GenerationRun> findFirstByProjectIdAndStatusAndDryRunFalseOrderByCreatedAtDescIdDesc(
      String projectId, RunStatus status);

  @Query(
      "select r from GenerationRun r where r.projectId = :projectId"
          + " order by r.createdAt desc, r.id desc")
  List<GenerationRun> findFirstPageByProjectId(@Param("projectId") String projectId, Pageable page);

  @Query(
      "select r from GenerationRun r where r.projectId = :projectId"
          + " and (r.createdAt < :createdAt or (r.createdAt = :createdAt and r.id < :id))"
          + " order by r.createdAt desc, r.id desc")
  List<GenerationRun> findPageAfter(
      @Param("projectId") String projectId,
      @Param("createdAt") Instant createdAt,
      @Param("id") String id,
      Pageable page);
}

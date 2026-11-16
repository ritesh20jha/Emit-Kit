package dev.emitkit.api.projects;

import dev.emitkit.domain.RunCursor;
import dev.emitkit.domain.RunStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One page of an organization's projects with their last-run fields. A single statement reads each
 * project's newest run and newest released version through lateral joins, so the list costs one
 * round trip however many projects it has.
 */
@Component
class ProjectListQuery {

  /**
   * The statement escapes LIKE wildcards with {@code !}, the character {@link #escapeLike} uses.
   */
  private static final String PAGE =
      """
      select p.id, p.repo_full_name, p.created_at,
             last_run.status as last_run_status,
             last_run.created_at as last_run_at,
             released.sdk_version as current_version
        from projects p
        left join lateral (
             select r.status, r.created_at
               from generation_runs r
              where r.project_id = p.id
              order by r.created_at desc, r.id desc
              limit 1) last_run on true
        left join lateral (
             select r.sdk_version
               from generation_runs r
              where r.project_id = p.id and r.sdk_version is not null and not r.dry_run
              order by r.created_at desc, r.id desc
              limit 1) released on true
       where p.org_id = :orgId
         and p.deleted_at is null
         and (cast(:namePattern as text) is null
              or p.repo_full_name ilike cast(:namePattern as text) escape '!')
         and (cast(:status as text) is null or last_run.status = cast(:status as text))
         and (cast(:after as timestamptz) is null
              or p.created_at < cast(:after as timestamptz)
              or (p.created_at = cast(:after as timestamptz) and p.id < :afterId))
       order by p.created_at desc, p.id desc
       limit :limit
      """;

  /** A listed project and the creation time that its list position is keyed on. */
  record Row(ProjectSummary summary, Instant createdAt) {}

  private final JdbcClient jdbc;

  ProjectListQuery(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Returns up to {@code limit} projects of {@code orgId} that are not deleted, newest first.
   *
   * @param q a text the repository name must contain, ignoring case, or {@code null} for no filter
   * @param status only projects whose last run has this status, or {@code null} for no filter
   * @param after the position just after the last project of the previous page, or {@code null}
   * @param limit the most rows to return
   */
  List<Row> page(
      String orgId,
      @Nullable String q,
      @Nullable RunStatus status,
      @Nullable RunCursor after,
      int limit) {
    return jdbc.sql(PAGE)
        .param("orgId", orgId)
        .param("namePattern", q == null ? null : "%" + escapeLike(q) + "%")
        .param("status", status == null ? null : status.name())
        .param("after", after == null ? null : toUtc(after.createdAt()))
        .param("afterId", after == null ? null : after.id())
        .param("limit", limit)
        .query((rs, rowNum) ->
            new Row(
                new ProjectSummary(
                    rs.getString("id"),
                    rs.getString("repo_full_name"),
                    rs.getString("last_run_status") == null
                        ? null
                        : RunStatus.valueOf(rs.getString("last_run_status")),
                    instant(rs.getObject("last_run_at", OffsetDateTime.class)),
                    rs.getString("current_version")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  /** Escapes the LIKE wildcards and the escape character itself in the user's text. */
  private static String escapeLike(String text) {
    return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static OffsetDateTime toUtc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static @Nullable Instant instant(@Nullable OffsetDateTime time) {
    return time == null ? null : time.toInstant();
  }
}

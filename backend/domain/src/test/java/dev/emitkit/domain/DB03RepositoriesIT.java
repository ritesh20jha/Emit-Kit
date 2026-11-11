package dev.emitkit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.id.Ids;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DB03RepositoriesIT extends PostgresIT {

  private static final Instant EPOCH = Instant.parse("2026-11-01T10:00:00Z");

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private SpecSnapshotRepository snapshots;
  @Autowired private GenerationRunRepository runs;
  @Autowired private GenerationRunLogRepository logs;
  @Autowired private JdbcTemplate jdbc;

  @PersistenceContext private EntityManager em;

  private User user;
  private Organization org;
  private Project project;
  private ProjectConfig config;

  @BeforeEach
  void seed() {
    user = users.saveAndFlush(new User(null, 1L, "octocat", "Octo", "o@example.com", "https://a"));
    org = orgs.saveAndFlush(new Organization(null, "github", "900", "acme", "acme", false, null));
    project =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                "acme/api",
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                user.getId()));
    config =
        configs.saveAndFlush(
            new ProjectConfig(
                null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, user.getId()));
  }

  @Test
  void snapshotUniquePerProjectAndHash() {
    Project other =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                "acme/web",
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                user.getId()));
    snapshots.saveAndFlush(snapshot(project.getId(), "hash-1"));
    snapshots.saveAndFlush(snapshot(other.getId(), "hash-1"));
    assertThat(snapshots.findByProjectIdAndContentHash(project.getId(), "hash-1"))
        .hasValueSatisfying(s -> assertThat(s.getProjectId()).isEqualTo(project.getId()));

    assertThatThrownBy(() -> snapshots.saveAndFlush(snapshot(project.getId(), "hash-1")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void findLastSuccessfulNonDryRun_ignoresDryRunsAndFailures() {
    Instant t1 = at(1);
    Instant t2 = at(2);
    Instant t3 = at(3);
    run(RunStatus.SUCCESS, false, t1);
    run(RunStatus.SUCCESS, true, t2);
    run(RunStatus.FAILED, false, t3);
    run(RunStatus.SUCCESS, false, at(0));
    assertThat(runs.findLastSuccessfulNonDryRun(project.getId()))
        .get()
        .extracting(GenerationRun::getCreatedAt)
        .isEqualTo(t1);
  }

  @Test
  void runList_isKeysetPaginatedNewestFirst() {
    for (int i = 1; i <= 5; i++) {
      run(RunStatus.SUCCESS, false, at(i));
    }

    List<GenerationRun> page1 = runs.findByProjectId(project.getId(), null, 2);
    assertThat(page1).extracting(GenerationRun::getCreatedAt).containsExactly(at(5), at(4));

    List<GenerationRun> page2 = runs.findByProjectId(project.getId(), cursorOf(page1), 2);
    assertThat(page2).extracting(GenerationRun::getCreatedAt).containsExactly(at(3), at(2));

    List<GenerationRun> page3 = runs.findByProjectId(project.getId(), cursorOf(page2), 2);
    assertThat(page3).extracting(GenerationRun::getCreatedAt).containsExactly(at(1));
  }

  @Test
  void runList_tieOnCreatedAt_pagesWithoutDuplicatesOrSkips() {
    Instant tie = at(2);
    GenerationRun older = run(RunStatus.SUCCESS, false, at(1));
    List<GenerationRun> sameInstant =
        List.of(
            run(RunStatus.SUCCESS, false, tie),
            run(RunStatus.SUCCESS, false, tie),
            run(RunStatus.SUCCESS, false, tie));
    em.clear();

    List<GenerationRun> page1 = runs.findByProjectId(project.getId(), null, 2);
    List<GenerationRun> page2 = runs.findByProjectId(project.getId(), cursorOf(page1), 2);
    List<GenerationRun> page3 = runs.findByProjectId(project.getId(), cursorOf(page2), 2);

    // Page 1 ends inside the tie group; page 2 finishes it and continues with the older run.
    assertThat(page1).extracting(GenerationRun::getCreatedAt).containsExactly(tie, tie);
    assertThat(page2).extracting(GenerationRun::getCreatedAt).containsExactly(tie, at(1));
    assertThat(page3).isEmpty();

    List<String> expected =
        Stream.concat(
                sameInstant.stream().map(GenerationRun::getId).sorted(Comparator.reverseOrder()),
                Stream.of(older.getId()))
            .toList();
    assertThat(Stream.of(page1, page2).flatMap(List::stream).map(GenerationRun::getId).toList())
        .containsExactlyElementsOf(expected);
  }

  @Test
  void logSeqUniquePerRun() {
    GenerationRun first = run(RunStatus.RUNNING, false, at(1));
    GenerationRun second = run(RunStatus.RUNNING, false, at(2));
    logs.saveAndFlush(log(first, 1, at(1)));
    logs.saveAndFlush(log(second, 1, at(2)));

    assertThatThrownBy(() -> logs.saveAndFlush(log(first, 1, at(3))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void logsAfterSeq_areOrderedAndRetentionDeletesOldLogs() {
    GenerationRun run = run(RunStatus.RUNNING, false, at(1));
    logs.saveAndFlush(log(run, 1, Instant.parse("2026-01-01T00:00:00Z")));
    logs.saveAndFlush(log(run, 2, at(2)));
    logs.saveAndFlush(log(run, 3, at(3)));

    assertThat(logs.findByRunIdAndSeqGreaterThanOrderBySeq(run.getId(), 1))
        .extracting(GenerationRunLog::getSeq)
        .containsExactly(2, 3);

    assertThat(logs.deleteByTsBefore(Instant.parse("2026-06-01T00:00:00Z"))).isEqualTo(1);
    assertThat(logs.findByRunIdAndSeqGreaterThanOrderBySeq(run.getId(), 0))
        .extracting(GenerationRunLog::getSeq)
        .containsExactly(2, 3);
  }

  @Test
  void jsonbStrings_storedAsRawJson() {
    GenerationRun run = run(RunStatus.SUCCESS, false, at(1));
    run.setDiffSummary("{\"added\": 1}");
    run.setStepOutputs("{\"FETCH_SPEC\": {\"commit\": \"abc\"}}");
    runs.saveAndFlush(run);
    em.clear();

    assertThat(
            jdbc.queryForObject(
                "select diff_summary->>'added' from generation_runs where id = ?",
                String.class,
                run.getId()))
        .isEqualTo("1");
    assertThat(runs.findById(run.getId()))
        .hasValueSatisfying(r -> {
          assertThat(r.getDiffSummary()).isEqualTo("{\"added\": 1}");
          assertThat(r.getStepOutputs()).isEqualTo("{\"FETCH_SPEC\": {\"commit\": \"abc\"}}");
        });
  }

  @Test
  void invalidStatus_rejectedByCheckConstraint() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO generation_runs (id, project_id, config_id, trigger, dry_run,"
                    + " status, cancel_requested, attempt, step_outputs, created_at)"
                    + " VALUES (?, ?, ?, 'MANUAL', false, 'BOGUS', false, 0, '{}', now())",
                Ids.newUlid(),
                project.getId(),
                config.getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("generation_runs_status_check");
  }

  @Test
  void stepNamesInPipelineOrder() {
    assertThat(StepName.values())
        .extracting(Enum::name)
        .containsExactly(
            "FETCH_SPEC",
            "PARSE_SPEC",
            "LINT_SPEC",
            "DIFF_SPEC",
            "CALC_VERSION",
            "BREAKING_GATE",
            "RUN_GENERATORS",
            "SYNTAX_CHECK",
            "PACKAGE_ARTIFACT",
            "COMMIT_OUTPUT",
            "FINALIZE");
  }

  private static Instant at(int seconds) {
    return EPOCH.plusSeconds(seconds);
  }

  private static String cursorOf(List<GenerationRun> page) {
    GenerationRun last = page.get(page.size() - 1);
    return RunCursor.encode(last.getCreatedAt(), last.getId());
  }

  private SpecSnapshot snapshot(String projectId, String contentHash) {
    return new SpecSnapshot(null, projectId, contentHash, "openapi: 3.1.0", "{}", "1.0.0");
  }

  private GenerationRun run(RunStatus status, boolean dryRun, Instant createdAt) {
    GenerationRun run =
        new GenerationRun(
            null, project.getId(), config.getId(), RunTrigger.MANUAL, dryRun, createdAt);
    run.setStatus(status);
    return runs.saveAndFlush(run);
  }

  private GenerationRunLog log(GenerationRun run, int seq, Instant ts) {
    return new GenerationRunLog(run.getId(), seq, ts, "INFO", StepName.FETCH_SPEC, "message");
  }
}

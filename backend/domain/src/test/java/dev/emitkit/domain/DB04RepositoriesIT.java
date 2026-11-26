package dev.emitkit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class DB04RepositoriesIT extends PostgresIT {

  private static final Instant EPOCH = Instant.parse("2026-11-01T10:00:00Z");

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private RunArtifactRepository artifacts;
  @Autowired private LintResultRepository lintResults;
  @Autowired private RunApprovalRepository approvals;
  @Autowired private SdkVersionRepository sdkVersions;

  @PersistenceContext private EntityManager em;

  private User user;
  private Organization org;
  private Project project;
  private ProjectConfig config;
  private GenerationRun run;

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
    run = runs.saveAndFlush(runAt(config, at(1)));
  }

  @Test
  void oneArtifactPerRun() {
    artifacts.saveAndFlush(artifact(run.getId(), at(1)));
    assertThat(artifacts.findByRunId(run.getId())).isPresent();

    assertThatThrownBy(() -> artifacts.saveAndFlush(artifact(run.getId(), at(2))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void manifestRoundTrips() {
    List<ManifestEntry> manifest =
        List.of(
            new ManifestEntry("src/client.ts", "a1b2c3", 1024, FileStatus.ADDED),
            new ManifestEntry("README.md", "d4e5f6", 512, FileStatus.SKIPPED));
    artifacts.saveAndFlush(
        new RunArtifact(null, run.getId(), "runs/1/bundle.tar.gz", 1536, 2, manifest, at(1)));
    em.clear();

    assertThat(artifacts.findByRunId(run.getId()))
        .hasValueSatisfying(a -> assertThat(a.getManifest()).isEqualTo(manifest));
  }

  @Test
  void artifactsCreatedBeforeCutoff_areFoundForRetention() {
    GenerationRun later = runs.saveAndFlush(runAt(config, at(2)));
    artifacts.saveAndFlush(artifact(run.getId(), at(1)));
    artifacts.saveAndFlush(artifact(later.getId(), at(3)));

    assertThat(artifacts.findByCreatedAtBefore(at(2)))
        .extracting(RunArtifact::getRunId)
        .containsExactly(run.getId());
  }

  @Test
  void lintCountsBySeverity() {
    lintResults.saveAndFlush(lint(Severity.ERROR, "$.paths./a"));
    lintResults.saveAndFlush(lint(Severity.ERROR, "$.paths./b"));
    lintResults.saveAndFlush(lint(Severity.INFO, "$.info"));

    List<SeverityCount> counts = lintResults.countBySeverity(run.getId());
    assertThat(
            counts.stream()
                .collect(Collectors.toMap(SeverityCount::severity, SeverityCount::count)))
        .isEqualTo(Map.of(Severity.ERROR, 2L, Severity.INFO, 1L));
    assertThat(lintResults.findByRunId(run.getId())).hasSize(3);
  }

  @Test
  void oneApprovalPerRun() {
    approvals.saveAndFlush(new RunApproval(null, run.getId(), at(1)));
    assertThat(approvals.findByRunId(run.getId()))
        .hasValueSatisfying(a -> assertThat(a.getDecision()).isNull());

    assertThatThrownBy(() -> approvals.saveAndFlush(new RunApproval(null, run.getId(), at(2))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void pendingApprovalIsDecidedInPlace() {
    RunApproval approval = approvals.saveAndFlush(new RunApproval(null, run.getId(), at(1)));
    approval.decide(user.getId(), Decision.APPROVED, "ship it", at(2));
    approvals.saveAndFlush(approval);
    em.clear();

    assertThat(approvals.findByRunId(run.getId()))
        .hasValueSatisfying(a -> {
          assertThat(a.getDecision()).isEqualTo(Decision.APPROVED);
          assertThat(a.getDecidedBy()).isEqualTo(user.getId());
          assertThat(a.getComment()).isEqualTo("ship it");
          assertThat(a.getDecidedAt()).isEqualTo(at(2));
        });
  }

  @Test
  void sdkVersionUniquePerProject() {
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
    sdkVersions.saveAndFlush(sdk(project.getId(), "1.0.0", at(1)));
    sdkVersions.saveAndFlush(sdk(other.getId(), "1.0.0", at(1)));
    assertThat(sdkVersions.existsByProjectIdAndVersion(project.getId(), "1.0.0")).isTrue();
    assertThat(sdkVersions.existsByProjectIdAndVersion(project.getId(), "2.0.0")).isFalse();

    assertThatThrownBy(() -> sdkVersions.saveAndFlush(sdk(project.getId(), "1.0.0", at(2))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void sdkVersionsListedNewestFirst() {
    sdkVersions.saveAndFlush(sdk(project.getId(), "1.0.0", at(1)));
    sdkVersions.saveAndFlush(sdk(project.getId(), "1.1.0", at(3)));
    sdkVersions.saveAndFlush(sdk(project.getId(), "1.0.1", at(2)));

    assertThat(sdkVersions.findByProjectIdOrderByCreatedAtDesc(project.getId()))
        .extracting(SdkVersion::getVersion)
        .containsExactly("1.1.0", "1.0.1", "1.0.0");
  }

  private static Instant at(int seconds) {
    return EPOCH.plusSeconds(seconds);
  }

  private GenerationRun runAt(ProjectConfig config, Instant createdAt) {
    return new GenerationRun(
        null, project.getId(), config.getId(), RunTrigger.MANUAL, false, createdAt);
  }

  private RunArtifact artifact(String runId, Instant createdAt) {
    return new RunArtifact(null, runId, "runs/" + runId + ".tar.gz", 10, 1, List.of(), createdAt);
  }

  private LintResult lint(Severity severity, String jsonPath) {
    return new LintResult(null, run.getId(), "rule-x", severity, jsonPath, "message");
  }

  private SdkVersion sdk(String projectId, String version, Instant createdAt) {
    return new SdkVersion(
        null, projectId, version, run.getId(), ChangeType.MINOR, "Changelog", createdAt);
  }
}

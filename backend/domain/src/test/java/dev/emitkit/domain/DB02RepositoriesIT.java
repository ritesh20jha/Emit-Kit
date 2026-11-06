package dev.emitkit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.id.Ids;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DB02RepositoriesIT extends PostgresIT {

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private JdbcTemplate jdbc;

  @PersistenceContext private EntityManager em;

  private User user;
  private Organization org;
  private Project project;

  @BeforeEach
  void seed() {
    user = users.saveAndFlush(new User(null, 1L, "octocat", "Octo", "o@example.com", "https://a"));
    org = newOrg("900", "acme");
    project = projects.saveAndFlush(newProject(org, "acme/api"));
  }

  @Test
  void sameRepoTwiceInOrg_violatesUnique() {
    assertThatThrownBy(() -> projects.saveAndFlush(newProject(org, "acme/api")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void sameRepoInTwoOrgs_isAllowed_andFoundByRepoAcrossOrgs() {
    Organization other = newOrg("901", "beta");
    projects.saveAndFlush(newProject(other, "acme/api"));
    assertThat(projects.findByRepoFullName("acme/api"))
        .extracting(Project::getOrgId)
        .containsExactlyInAnyOrder(org.getId(), other.getId());
  }

  @Test
  void findByOrgId_returnsOnlyThatOrgsProjects() {
    projects.saveAndFlush(newProject(org, "acme/web"));
    projects.saveAndFlush(newProject(newOrg("902", "gamma"), "gamma/api"));
    assertThat(projects.findByOrgId(org.getId()))
        .extracting(Project::getRepoFullName)
        .containsExactlyInAnyOrder("acme/api", "acme/web");
  }

  @Test
  void existsByOrgIdAndRepoFullName_isScopedToOrg() {
    Organization other = newOrg("901", "beta");
    assertThat(projects.existsByOrgIdAndRepoFullName(org.getId(), "acme/api")).isTrue();
    assertThat(projects.existsByOrgIdAndRepoFullName(other.getId(), "acme/api")).isFalse();
  }

  @Test
  void invalidOutputMode_rejectedByCheckConstraint() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO projects (id, org_id, repo_full_name, spec_path, default_branch,"
                    + " output_mode, created_by, created_at, updated_at)"
                    + " VALUES (?, ?, 'acme/bad', 'openapi.yaml', 'main', 'BOGUS', ?, now(), now())",
                Ids.newUlid(),
                org.getId(),
                user.getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("projects_output_mode_check");
  }

  @Test
  void findLatest_returnsHighestVersion() {
    configs.save(config(2));
    configs.save(config(1));
    assertThat(configs.findLatest(project.getId()))
        .get()
        .extracting(ProjectConfig::getVersion)
        .isEqualTo(2);
  }

  @Test
  void duplicateConfigVersion_violatesUnique() {
    configs.saveAndFlush(config(1));
    assertThatThrownBy(() -> configs.saveAndFlush(config(1)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void nextVersion_startsAtOneAndIncrements() {
    assertThat(configs.nextVersion(project.getId())).isEqualTo(1);
    configs.saveAndFlush(config(1));
    configs.saveAndFlush(config(2));
    assertThat(configs.nextVersion(project.getId())).isEqualTo(3);
  }

  @Test
  void jsonbListsRoundTrip() {
    ProjectConfig c = config(1);
    c.setOutputs(List.of(OutputKind.SDK, OutputKind.MCP));
    c.setSdkLanguages(List.of("typescript", "java"));
    configs.saveAndFlush(c);
    em.clear();
    assertThat(configs.findById(c.getId()))
        .hasValueSatisfying(r -> {
          assertThat(r.getOutputs()).containsExactly(OutputKind.SDK, OutputKind.MCP);
          assertThat(r.getSdkLanguages()).containsExactly("typescript", "java");
        });
  }

  @Test
  void languageSpecificColumns_roundTripAsNull() {
    ProjectConfig c = config(1);
    c.setNpmScope(null);
    c.setPypiName(null);
    c.setMavenGroupId(null);
    configs.saveAndFlush(c);
    em.clear();
    assertThat(configs.findById(c.getId()))
        .hasValueSatisfying(r -> {
          assertThat(r.getNpmScope()).isNull();
          assertThat(r.getPypiName()).isNull();
          assertThat(r.getMavenGroupId()).isNull();
        });
  }

  @Test
  void defaults_appliedByDatabaseAndByJavaConstructor() {
    String id = Ids.newUlid();
    jdbc.update(
        "INSERT INTO project_configs (id, project_id, version, version_strategy, created_by,"
            + " created_at) VALUES (?, ?, 1, 'EMITKIT_MANAGED', ?, now())",
        id,
        project.getId(),
        user.getId());
    em.clear();
    assertThat(configs.findById(id))
        .hasValueSatisfying(r -> {
          assertThat(r.getOutputDir()).isEqualTo(".emitkit/");
          assertThat(r.getBreakingGate()).isEqualTo(BreakingGate.ALLOW);
          assertThat(r.getLintFailOn()).isEqualTo(LintFailOn.NEVER);
          assertThat(r.getAiProvider()).isEqualTo("gemini");
          assertThat(r.getOutputs()).isEmpty();
          assertThat(r.getSdkLanguages()).isEmpty();
        });

    ProjectConfig built = config(1);
    assertThat(built.getOutputDir()).isEqualTo(".emitkit/");
    assertThat(built.getBreakingGate()).isEqualTo(BreakingGate.ALLOW);
    assertThat(built.getLintFailOn()).isEqualTo(LintFailOn.NEVER);
    assertThat(built.getAiProvider()).isEqualTo("gemini");
    assertThat(built.getOutputs()).isEmpty();
    assertThat(built.getSdkLanguages()).isEmpty();
  }

  private Organization newOrg(String vcsOrgId, String slug) {
    return orgs.saveAndFlush(new Organization(null, "github", vcsOrgId, slug, slug, false, null));
  }

  private Project newProject(Organization owner, String repoFullName) {
    return new Project(
        null, owner.getId(), repoFullName, "openapi.yaml", "main", OutputMode.APPEND, user.getId());
  }

  private ProjectConfig config(int version) {
    return new ProjectConfig(
        null, project.getId(), version, VersionStrategy.EMITKIT_MANAGED, user.getId());
  }
}

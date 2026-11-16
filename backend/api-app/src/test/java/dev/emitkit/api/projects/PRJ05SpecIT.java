package dev.emitkit.api.projects;

import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRJ-05: reading and saving the API spec of a project. Runs against Postgres and Redis, with the
 * VCS provider replaced by an in-memory fake that holds the spec file.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class PRJ05SpecIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SPEC_PATH = "openapi.yaml";
  private static final String SPEC =
      """
      openapi: 3.1.0
      info:
        title: Pets
        version: 1.2.3
      paths: {}
      """;
  private static final String EDITED =
      """
      openapi: 3.1.0
      info:
        title: Pets
        version: 1.3.0
      paths: {}
      """;

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;

  @MockitoBean private VcsProvider vcsProvider;

  private InMemoryVcsClient vcs;

  @BeforeEach
  void resetData() {
    configs.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
    vcs = new InMemoryVcsClient();
    when(vcsProvider.forProject(anyString())).thenReturn(vcs);
    vcs.createRepo("acme", "api", false);
    vcs.putFile("acme/api", SPEC_PATH, SPEC, null, "add spec", "main");
  }

  @Test
  void get_returnsContentShaFormat() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    Project yamlProject = project(acme, octo, "acme/api", SPEC_PATH);

    mvc.perform(get("/api/v1/projects/{id}/spec", yamlProject.getId()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value(SPEC))
        .andExpect(jsonPath("$.sha").value(GitBlob.sha(SPEC)))
        .andExpect(jsonPath("$.format").value("yaml"));

    vcs.createRepo("acme", "json", false);
    vcs.putFile("acme/json", "openapi.json", "{}", null, "add spec", "main");
    Project jsonProject = project(acme, octo, "acme/json", "openapi.json");

    mvc.perform(get("/api/v1/projects/{id}/spec", jsonProject.getId()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.format").value("json"));
  }

  @Test
  void staleSha_returns409() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    Project project = project(acme, octo, "acme/api", SPEC_PATH);
    String staleSha = GitBlob.sha(SPEC);
    otherUserSaves(EDITED, staleSha);
    String currentSha = GitBlob.sha(EDITED);

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(saveBody("x", staleSha, null, false)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SPEC_CONFLICT"))
        .andExpect(jsonPath("$.currentSha").value(currentSha));

    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(EDITED);
  }

  @Test
  void get_jsonExtensionIgnoresCase() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    vcs.createRepo("acme", "upper", false);
    vcs.putFile("acme/upper", "SPEC.JSON", "{}", null, "add spec", "main");
    Project project = project(acme, octo, "acme/upper", "SPEC.JSON");

    mvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.format").value("json"));
  }

  @Test
  void conflictOnUnchangedFile_isNotSpecConflict() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    Project project = project(acme, octo, "acme/api", SPEC_PATH);
    // The provider refuses the write (a branch protection rule, say) while the file is unchanged.
    vcs.failNext("putFile", new VcsConflictException("branch protection blocks this push"));

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(saveBody(EDITED, GitBlob.sha(SPEC), null, false)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VCS_CONFLICT"))
        .andExpect(jsonPath("$.detail").value("branch protection blocks this push"))
        .andExpect(jsonPath("$.currentSha").doesNotExist());

    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(SPEC);
  }

  @Test
  void conflictThenFileGone_returns404SpecNotFound() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    Project project = project(acme, octo, "acme/api", SPEC_PATH);
    vcs.failNext("putFile", new VcsConflictException("stale sha"));
    // The file is gone when the conflict is checked by reading it again.
    vcs.failNext("getFile", new VcsNotFoundException("file not found: " + SPEC_PATH));

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(saveBody(EDITED, GitBlob.sha(SPEC), null, false)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPEC_NOT_FOUND"));
  }

  @Test
  void save_returnsCommitAndNewSha() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    Project project = project(acme, octo, "acme/api", SPEC_PATH);
    String sha = GitBlob.sha(SPEC);

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(saveBody(EDITED, sha, null, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commitSha").isNotEmpty())
        .andExpect(jsonPath("$.newSha").value(GitBlob.sha(EDITED)))
        .andExpect(jsonPath("$", hasKey("runId")))
        .andExpect(jsonPath("$.runId").value(nullValue()))
        .andExpect(jsonPath("$", hasKey("runError")))
        .andExpect(jsonPath("$.runError").value(nullValue()));
    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(EDITED);

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(saveBody(SPEC, GitBlob.sha(EDITED), "Revert pets version", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.newSha").value(sha));
    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(SPEC);
  }

  @Test
  void tooLarge_413() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    Project project = project(acme, octo, "acme/api", SPEC_PATH);
    // 2^20 + 1 characters of two UTF-8 bytes each: under the character count, over the byte limit.
    String tooLarge = "é".repeat(1024 * 1024 + 1);

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(saveBody(tooLarge, GitBlob.sha(SPEC), null, false)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("SPEC_TOO_LARGE"));

    assertNoSaveReachedVcs();
    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(SPEC);
  }

  @Test
  void viewerPut_403() throws Exception {
    Organization acme = org("acme");
    User viewer = user(1, "viewer");
    member(acme, viewer, VIEWER);
    Project project = project(acme, viewer, "acme/api", SPEC_PATH);

    mvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(signedInAs(viewer)))
        .andExpect(status().isOk());

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(viewer))
                .contentType("application/json")
                .content(saveBody(EDITED, GitBlob.sha(SPEC), null, false)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertNoSaveReachedVcs();
    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(SPEC);
  }

  @Test
  void trackedBranchDiffersFromRepoDefault_savesToTrackedBranch() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    String develop = "openapi: 3.1.0\ninfo:\n  title: Dogs\n  version: 2.0.0\npaths: {}\n";
    vcs.createBranch("acme/api", "develop", vcs.branchHead("acme/api", "main"));
    vcs.putFile("acme/api", SPEC_PATH, develop, GitBlob.sha(SPEC), "develop edit", "develop");
    Project project = project(acme, octo, "acme/api", SPEC_PATH, "develop");

    mvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value(develop))
        .andExpect(jsonPath("$.sha").value(GitBlob.sha(develop)));

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(saveBody(EDITED, GitBlob.sha(develop), null, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.newSha").value(GitBlob.sha(EDITED)));

    assertThat(vcs.getFile("acme/api", SPEC_PATH, "develop").content()).isEqualTo(EDITED);
    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(SPEC);
  }

  @Test
  void editorNotCreator_usesCreatorToken() throws Exception {
    Organization acme = org("acme");
    User creator = user(1, "creator");
    member(acme, creator, Role.ADMIN);
    User editor = user(2, "editor");
    member(acme, editor, Role.EDITOR);
    Project project = project(acme, creator, "acme/api", SPEC_PATH);
    // Only the creator's token yields a client; any other caller's token makes the request fail.
    doThrow(new IllegalStateException("not the creator's token"))
        .when(vcsProvider)
        .forProject(anyString());
    doReturn(vcs).when(vcsProvider).forProject(creator.getId());

    mvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(signedInAs(editor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sha").value(GitBlob.sha(SPEC)));

    mvc.perform(
            put("/api/v1/projects/{id}/spec", project.getId())
                .with(csrf())
                .with(signedInAs(editor))
                .contentType("application/json")
                .content(saveBody(EDITED, GitBlob.sha(SPEC), null, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.newSha").value(GitBlob.sha(EDITED)));

    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(EDITED);
    verify(vcsProvider, times(2)).forProject(creator.getId());
    verify(vcsProvider, never()).forProject(editor.getId());
  }

  @Test
  void nonMember_gets404() throws Exception {
    Organization acme = org("acme");
    User creator = user(1, "creator");
    member(acme, creator, Role.ADMIN);
    User outsider = user(2, "outsider");
    Project project = project(acme, creator, "acme/api", SPEC_PATH);

    mvc.perform(get("/api/v1/projects/{id}/spec", project.getId()).with(signedInAs(outsider)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("project not found"));

    assertThat(vcs.calls("getFile")).isEmpty();
  }

  /** Only the fixture write from {@link #resetData()} has reached the VCS, so no save did. */
  private void assertNoSaveReachedVcs() {
    assertThat(vcs.calls("putFile")).hasSize(1);
  }

  /** Another signed-in user saves the file, so the sha the first user read is stale. */
  private void otherUserSaves(String content, String expectedSha) {
    vcs.putFile("acme/api", SPEC_PATH, content, expectedSha, "someone else's edit", "main");
  }

  private static String saveBody(String content, String sha, String message, boolean generate) {
    return JSON.writeValueAsString(new SaveSpecRequest(content, sha, message, generate));
  }

  private Project project(Organization org, User user, String repo, String specPath) {
    return project(org, user, repo, specPath, "main");
  }

  private Project project(
      Organization org, User user, String repo, String specPath, String branch) {
    return projects.save(
        new Project(null, org.getId(), repo, specPath, branch, OutputMode.APPEND, user.getId()));
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }

  private User user(long githubId, String login) {
    return users.save(
        new User(null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
  }

  private Organization org(String slug) {
    return organizations.save(new Organization(null, null, "vcs-" + slug, slug, slug, false, null));
  }

  private void member(Organization org, User user, Role role) {
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, RoleSource.SYNC));
  }
}

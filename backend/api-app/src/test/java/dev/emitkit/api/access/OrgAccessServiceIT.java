package dev.emitkit.api.access;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.EDITOR;
import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberId;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The org and project access rules, end to end: 404 for non-members, 403 for members whose role
 * lacks the permission, and the org list limited to the caller's memberships.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class OrgAccessServiceIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ProjectAccess projectAccess;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private ProjectRepository projects;
  @Autowired private OAuthAccountRepository oauth;

  @BeforeEach
  void resetData() {
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
  }

  @AfterEach
  void clearSecurityContext() {
    TestSecurityContextHolder.clearContext();
  }

  @Test
  void nonMember_gets404NotFound() throws Exception {
    Organization acme = org("acme");
    User stranger = user(3, "stranger", null);

    signedInGet("/api/v1/orgs/" + acme.getId(), stranger)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void viewerPatchingRole_gets403() throws Exception {
    Organization acme = org("acme");
    User viewer = user(1, "viewer", null);
    member(acme, viewer, VIEWER);
    User target = user(2, "target", null);
    member(acme, target, EDITOR);

    mvc.perform(
            patch("/api/v1/orgs/{orgId}/members/{userId}", acme.getId(), target.getId())
                .with(csrf())
                .with(signedInAs(viewer))
                .contentType("application/json")
                .content("{\"role\":\"VIEWER\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    OrgMember unchanged =
        orgMembers.findById(new OrgMemberId(acme.getId(), target.getId())).orElseThrow();
    assertThat(unchanged.getRole()).isEqualTo(EDITOR);
    assertThat(unchanged.getRoleSource()).isEqualTo(RoleSource.SYNC);
  }

  @Test
  void orgsList_onlyMyOrgs_withMemberCount() throws Exception {
    User me = user(1, "me", null);
    User other = user(2, "other", null);
    Organization acme = org("acme");
    member(acme, me, ADMIN);
    member(acme, other, VIEWER);
    Organization beta = org("beta");
    member(beta, me, VIEWER);
    Organization gamma = org("gamma");
    member(gamma, other, ADMIN);

    signedInGet("/api/v1/orgs", me)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.slug=='acme')].role").value("ADMIN"))
        .andExpect(jsonPath("$[?(@.slug=='acme')].memberCount").value(2))
        .andExpect(jsonPath("$[?(@.slug=='beta')].role").value("VIEWER"))
        .andExpect(jsonPath("$[?(@.slug=='beta')].memberCount").value(1));
  }

  @Test
  void projectAccess_missingProject_isNotFound() {
    signIn(user(1, "me", null));

    assertThatExceptionOfType(AppException.class)
        .isThrownBy(
            () -> projectAccess.require("01MISSINGPROJECT0000000000", Permission.PROJECT_VIEW))
        .extracting(AppException::code)
        .isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  void projectAccess_viewerLackingProjectDelete_isForbidden() {
    Organization acme = org("acme");
    User owner = user(1, "owner", null);
    member(acme, owner, ADMIN);
    User viewer = user(2, "viewer", null);
    member(acme, viewer, VIEWER);
    Project widgets = project(acme, owner);

    signIn(viewer);

    assertThatExceptionOfType(AppException.class)
        .isThrownBy(() -> projectAccess.require(widgets.getId(), Permission.PROJECT_DELETE))
        .extracting(AppException::code)
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  void projectAccess_memberWithPermission_returnsProject() {
    Organization acme = org("acme");
    User owner = user(1, "owner", null);
    member(acme, owner, ADMIN);
    User viewer = user(2, "viewer", null);
    member(acme, viewer, VIEWER);
    Project widgets = project(acme, owner);

    signIn(viewer);

    Project found = projectAccess.require(widgets.getId(), Permission.PROJECT_VIEW);
    assertThat(found.getId()).isEqualTo(widgets.getId());
    assertThat(found.getOrgId()).isEqualTo(acme.getId());
  }

  @Test
  void projectAccess_nonMember_isNotFoundWithSameMessageAsMissingProject() {
    Organization acme = org("acme");
    User owner = user(1, "owner", null);
    member(acme, owner, ADMIN);
    Project widgets = project(acme, owner);
    User stranger = user(2, "stranger", null);

    signIn(stranger);
    AppException missing =
        catchThrowableOfType(
            () -> projectAccess.require("01MISSINGPROJECT0000000000", Permission.PROJECT_VIEW),
            AppException.class);
    AppException nonMember =
        catchThrowableOfType(
            () -> projectAccess.require(widgets.getId(), Permission.PROJECT_VIEW),
            AppException.class);

    assertThat(nonMember.code()).isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(nonMember.getMessage()).isEqualTo(missing.getMessage());
  }

  private ResultActions signedInGet(String path, User user) throws Exception {
    return mvc.perform(get(path).with(signedInAs(user)));
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(tokenFor(user));
  }

  private static void signIn(User user) {
    TestSecurityContextHolder.setAuthentication(tokenFor(user));
  }

  private static OAuth2AuthenticationToken tokenFor(User user) {
    return new OAuth2AuthenticationToken(
        new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github");
  }

  private User user(long githubId, String login, String name) {
    return users.save(
        new User(null, githubId, login, name, null, "https://avatars.example.com/u/" + githubId));
  }

  private Organization org(String slug) {
    return organizations.save(new Organization(null, null, "vcs-" + slug, slug, slug, false, null));
  }

  private void member(Organization org, User user, Role role) {
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, RoleSource.SYNC));
  }

  private Project project(Organization org, User creator) {
    return projects.save(
        new Project(
            null,
            org.getId(),
            "acme/widgets",
            "openapi.yaml",
            "main",
            OutputMode.APPEND,
            creator.getId()));
  }
}

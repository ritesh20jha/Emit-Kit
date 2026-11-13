package dev.emitkit.api.orgs;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
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
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberId;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The members endpoints: listing with user profile fields, and changing a role as an admin. */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class MembersControllerIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String MEMBERS = "/api/v1/orgs/{orgId}/members";

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private OAuthAccountRepository oauth;

  @BeforeEach
  void resetData() {
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
  }

  @Test
  void listMembers_returnsProfileAndRoleSource() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo", "Octo Cat");
    member(acme, octo, ADMIN, RoleSource.SYNC);
    User bob = user(2, "bob", null);
    member(acme, bob, VIEWER, RoleSource.MANUAL);

    mvc.perform(get(MEMBERS, acme.getId()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.login=='octo')].userId").value(octo.getId()))
        .andExpect(jsonPath("$[?(@.login=='octo')].name").value("Octo Cat"))
        .andExpect(
            jsonPath("$[?(@.login=='octo')].avatarUrl").value("https://avatars.example.com/u/1"))
        .andExpect(jsonPath("$[?(@.login=='octo')].roleSource").value("SYNC"))
        .andExpect(jsonPath("$[?(@.login=='bob')].roleSource").value("MANUAL"))
        .andExpect(jsonPath("$[?(@.login=='bob')].role").value("VIEWER"));
  }

  @Test
  void adminSetsRole_markedManual() throws Exception {
    Organization acme = org("acme");
    User admin = user(1, "admin", null);
    member(acme, admin, ADMIN, RoleSource.SYNC);
    User target = user(2, "target", null);
    member(acme, target, VIEWER, RoleSource.SYNC);

    mvc.perform(
            patch(MEMBERS + "/{userId}", acme.getId(), target.getId())
                .with(csrf())
                .with(signedInAs(admin))
                .contentType("application/json")
                .content("{\"role\":\"EDITOR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(target.getId()))
        .andExpect(jsonPath("$.login").value("target"))
        .andExpect(jsonPath("$.role").value("EDITOR"))
        .andExpect(jsonPath("$.roleSource").value("MANUAL"));

    OrgMember saved =
        orgMembers.findById(new OrgMemberId(acme.getId(), target.getId())).orElseThrow();
    assertThat(saved.getRole()).isEqualTo(Role.EDITOR);
    assertThat(saved.getRoleSource()).isEqualTo(RoleSource.MANUAL);
  }

  @Test
  void demotingLastAdmin_rejected() throws Exception {
    Organization acme = org("acme");
    User admin = user(1, "admin", null);
    member(acme, admin, ADMIN, RoleSource.SYNC);

    mvc.perform(
            patch(MEMBERS + "/{userId}", acme.getId(), admin.getId())
                .with(csrf())
                .with(signedInAs(admin))
                .contentType("application/json")
                .content("{\"role\":\"VIEWER\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.detail").value("An organization needs at least one admin"));

    OrgMember saved =
        orgMembers.findById(new OrgMemberId(acme.getId(), admin.getId())).orElseThrow();
    assertThat(saved.getRole()).isEqualTo(ADMIN);
    assertThat(saved.getRoleSource()).isEqualTo(RoleSource.SYNC);
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }

  private User user(long githubId, String login, String name) {
    return users.save(
        new User(null, githubId, login, name, null, "https://avatars.example.com/u/" + githubId));
  }

  private Organization org(String slug) {
    return organizations.save(new Organization(null, null, "vcs-" + slug, slug, slug, false, null));
  }

  private void member(Organization org, User user, Role role, RoleSource source) {
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, source));
  }
}

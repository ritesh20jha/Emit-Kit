package dev.emitkit.api.orgs;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Autowired private PlatformTransactionManager transactions;

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

  @Test
  void roleChange_waitsForOrgLock() throws Exception {
    Organization acme = org("acme");
    User admin = user(1, "admin", null);
    member(acme, admin, ADMIN, RoleSource.SYNC);
    User target = user(2, "target", null);
    member(acme, target, VIEWER, RoleSource.SYNC);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // Another transaction holds the org lock, as a concurrent role change would.
      Future<?> holder =
          pool.submit(() ->
              new TransactionTemplate(transactions)
                  .executeWithoutResult(status -> {
                    organizations.findByIdForUpdate(acme.getId());
                    locked.countDown();
                    awaitQuietly(release);
                  }));
      locked.await();
      Future<Integer> change = pool.submit(() -> patchRole(acme, target, admin, "EDITOR"));

      // While the lock is held the change cannot finish: this is what the lock guarantees.
      assertThatThrownBy(() -> change.get(1, TimeUnit.SECONDS))
          .isInstanceOf(TimeoutException.class);
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
      assertThat(change.get(10, TimeUnit.SECONDS)).isEqualTo(200);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentDemotions_keepOneAdmin() throws Exception {
    Organization acme = org("acme");
    User a = user(1, "a", null);
    member(acme, a, ADMIN, RoleSource.SYNC);
    User b = user(2, "b", null);
    member(acme, b, ADMIN, RoleSource.SYNC);
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // Each admin demotes the other at the same moment.
      Future<Integer> aDemotesB =
          pool.submit(() -> {
            start.await();
            return patchRole(acme, b, a, "VIEWER");
          });
      Future<Integer> bDemotesA =
          pool.submit(() -> {
            start.await();
            return patchRole(acme, a, b, "VIEWER");
          });
      List<Integer> statuses =
          List.of(aDemotesB.get(30, TimeUnit.SECONDS), bDemotesA.get(30, TimeUnit.SECONDS));

      assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
      assertThat(statuses)
          .filteredOn(status -> status != 200)
          .allMatch(status -> status == 400 || status == 403);
      assertThat(orgMembers.countByOrgIdAndRole(acme.getId(), ADMIN)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private int patchRole(Organization org, User target, User requester, String role)
      throws Exception {
    return mvc.perform(
            patch(MEMBERS + "/{userId}", org.getId(), target.getId())
                .with(csrf())
                .with(signedInAs(requester))
                .contentType("application/json")
                .content("{\"role\":\"" + role + "\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
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

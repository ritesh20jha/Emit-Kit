package dev.emitkit.api.orgs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.api.auth.UserLoggedInEvent;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.VcsOrg.VcsRole;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsServerException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class OrgSyncServiceTest {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the authentication needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  /** The GitHub side: every test sets the organizations GitHub returns. */
  @MockitoBean private VcsProvider vcsProvider;

  @Autowired private OrgSyncService sync;
  @Autowired private OrgSyncListener listener;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private ApplicationContext context;
  @Autowired private MockMvc mvc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;

  private final InMemoryVcsClient vcs = new InMemoryVcsClient();
  private User user;

  @BeforeEach
  void setUp() {
    orgMembers.deleteAll();
    organizations.deleteAll();
    users.deleteAll();
    user = users.save(new User(null, 42, "octo", "Octo Cat", null, "https://avatars.example/42"));
    when(vcsProvider.forProject(anyString(), any(RateLimitPolicy.class))).thenReturn(vcs);
  }

  @Test
  void ownerBecomesAdmin_memberBecomesEditor() {
    vcs.orgs(org("acme", VcsRole.OWNER), org("beta", VcsRole.MEMBER));

    sync.sync(user.getId());

    assertThat(roles(user.getId()))
        .containsEntry("acme", Role.ADMIN)
        .containsEntry("beta", Role.EDITOR);
    assertThat(
            organizations.findByVcsProviderAndVcsOrgId("github", "id-beta").orElseThrow().getName())
        .as("a null GitHub name falls back to the login")
        .isEqualTo("beta");
  }

  @Test
  void personalOrgCreatedAsAdmin() {
    vcs.orgs(new VcsOrg("1001", "octo", "Octo Cat", true, VcsRole.OWNER));

    sync.sync(user.getId());

    Organization personal =
        organizations.findByVcsProviderAndVcsOrgId("github", "1001").orElseThrow();
    assertThat(personal.isPersonal()).isTrue();
    assertThat(personal.getOwnerUserId()).isEqualTo(user.getId());
    assertThat(personal.getSlug()).isEqualTo("octo");
    assertThat(personal.getName()).isEqualTo("Octo Cat");
    assertThat(roles(user.getId())).containsEntry("octo", Role.ADMIN);
    assertThat(orgMembers.findByUserId(user.getId()))
        .extracting(OrgMember::getRoleSource)
        .containsExactly(RoleSource.SYNC);
  }

  @Test
  void manualRoleIsNotOverwritten() {
    membership("acme", Role.VIEWER, RoleSource.MANUAL);
    vcs.orgs(org("acme", VcsRole.OWNER));

    sync.sync(user.getId());

    assertThat(roles(user.getId())).containsEntry("acme", Role.VIEWER);
    assertThat(orgMembers.findByUserId(user.getId()))
        .extracting(OrgMember::getRoleSource)
        .containsExactly(RoleSource.MANUAL);
  }

  @Test
  void removedFromGitHubOrg_loseSyncMembership_keepManual() {
    membership("acme", Role.ADMIN, RoleSource.SYNC);
    membership("beta", Role.VIEWER, RoleSource.MANUAL);
    vcs.orgs(org("gamma", VcsRole.MEMBER));

    sync.sync(user.getId());

    assertThat(roles(user.getId()))
        .containsOnlyKeys("beta", "gamma")
        .containsEntry("beta", Role.VIEWER)
        .containsEntry("gamma", Role.EDITOR);
  }

  @Test
  void syncTwice_isIdempotent() {
    vcs.orgs(org("acme", VcsRole.OWNER), org("beta", VcsRole.MEMBER));

    sync.sync(user.getId());
    Map<String, Role> afterFirst = roles(user.getId());
    sync.sync(user.getId());

    assertThat(roles(user.getId())).isEqualTo(afterFirst).hasSize(2);
    assertThat(organizations.count()).isEqualTo(2);
    assertThat(orgMembers.count()).isEqualTo(2);
  }

  @Test
  void githubRoleChange_updatesSyncMembership() {
    vcs.orgs(org("acme", VcsRole.MEMBER));
    sync.sync(user.getId());
    assertThat(roles(user.getId())).containsEntry("acme", Role.EDITOR);

    vcs.orgs(org("acme", VcsRole.OWNER));
    sync.sync(user.getId());

    assertThat(roles(user.getId())).containsEntry("acme", Role.ADMIN);
  }

  @Test
  void loginSync_runsOnVirtualThread() throws Exception {
    vcs.orgs(org("acme", VcsRole.OWNER));
    AtomicReference<Thread> syncThread = new AtomicReference<>();
    when(vcsProvider.forProject(anyString(), any(RateLimitPolicy.class)))
        .thenAnswer(call -> {
          syncThread.set(Thread.currentThread());
          return vcs;
        });

    events.publishEvent(new UserLoggedInEvent(user.getId()));

    // The listener is asynchronous, so wait for its effect.
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (roles(user.getId()).isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(roles(user.getId())).containsEntry("acme", Role.ADMIN);
    assertThat(syncThread.get()).isNotNull().matches(Thread::isVirtual, "a virtual thread");
  }

  @Test
  void listenerFailure_isSwallowed() {
    vcs.failNext("listOrgs", new VcsServerException("GitHub is down"));

    assertThatCode(() -> new OrgSyncListener(sync).onLogin(new UserLoggedInEvent(user.getId())))
        .doesNotThrowAnyException();
    assertThat(organizations.count()).isZero();
  }

  @Test
  void syncEndpoint_throttled() throws Exception {
    vcs.orgs(org("acme", VcsRole.OWNER));
    var caller = signedIn();

    mvc.perform(post("/api/v1/me/sync-orgs").with(csrf()).with(caller))
        .andExpect(status().isNoContent());

    assertThat(roles(user.getId())).containsEntry("acme", Role.ADMIN);
    assertThat(redis.getExpire("orgsync:throttle:" + user.getId())).isBetween(1L, 60L);

    vcs.orgs();
    mvc.perform(post("/api/v1/me/sync-orgs").with(csrf()).with(caller))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.detail").value("Sync ran less than a minute ago"));

    assertThat(roles(user.getId()))
        .as("the throttled call does not sync")
        .containsEntry("acme", Role.ADMIN);
  }

  @Test
  void failedSync_doesNotThrottleNextCall() throws Exception {
    vcs.failNext("listOrgs", new VcsServerException("GitHub is down"));
    RequestPostProcessor caller = signedIn();

    mvc.perform(post("/api/v1/me/sync-orgs").with(csrf()).with(caller))
        .andExpect(status().is5xxServerError());
    assertThat(redis.hasKey("orgsync:throttle:" + user.getId()))
        .as("the claim is released when the sync fails")
        .isFalse();

    vcs.orgs(org("acme", VcsRole.OWNER));
    mvc.perform(post("/api/v1/me/sync-orgs").with(csrf()).with(caller))
        .andExpect(status().isNoContent());
    assertThat(roles(user.getId())).containsEntry("acme", Role.ADMIN);

    mvc.perform(post("/api/v1/me/sync-orgs").with(csrf()).with(caller))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void gitHubCallRunsOutsideTransaction() {
    vcs.orgs(org("acme", VcsRole.OWNER));
    AtomicBoolean inTransaction = new AtomicBoolean(true);
    when(vcsProvider.forProject(anyString(), any(RateLimitPolicy.class)))
        .thenAnswer(call -> {
          inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
          return vcs;
        });

    sync.sync(user.getId());

    assertThat(inTransaction.get())
        .as("GitHub is called before any transaction opens, so no connection is held across it")
        .isFalse();
    assertThat(roles(user.getId())).containsEntry("acme", Role.ADMIN);
  }

  @Test
  void defaultTaskExecutorStillAvailable() {
    assertThat(context.containsBean("applicationTaskExecutor"))
        .as("the orgSyncExecutor bean does not suppress Boot's default executor")
        .isTrue();
  }

  private static VcsOrg org(String login, VcsRole role) {
    return new VcsOrg("id-" + login, login, null, false, role);
  }

  /** Adds a membership of the signed-in test user in an organization that already exists. */
  private void membership(String login, Role role, RoleSource source) {
    Organization org =
        organizations.save(
            new Organization(null, "github", "id-" + login, login, login, false, null));
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, source));
  }

  /** A request as the signed-in test user. */
  private RequestPostProcessor signedIn() {
    return authentication(
        new UsernamePasswordAuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  /** The user's roles keyed by organization slug, which is the GitHub login for synced orgs. */
  private Map<String, Role> roles(String userId) {
    Map<String, Role> roles = new HashMap<>();
    for (OrgMember member : orgMembers.findByUserId(userId)) {
      roles.put(
          organizations.findById(member.getOrgId()).orElseThrow().getSlug(), member.getRole());
    }
    return roles;
  }
}

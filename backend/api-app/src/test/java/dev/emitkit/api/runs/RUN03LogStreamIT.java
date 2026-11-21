package dev.emitkit.api.runs;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.RunStatus.RUNNING;
import static dev.emitkit.domain.RunStatus.SUCCESS;
import static dev.emitkit.domain.RunTrigger.MANUAL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** RUN-03: the stored log of a run, and the live log stream over Redis. */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class RUN03LogStreamIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String LOGS = "/api/v1/runs/{runId}/logs";
  private static final String STREAM = "/api/v1/runs/{runId}/logs/stream";
  private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository generationRuns;
  @Autowired private GenerationRunLogRepository logs;
  @Autowired private OAuthAccountRepository oauth;

  @BeforeEach
  void resetData() {
    logs.deleteAllInBatch();
    generationRuns.deleteAllInBatch();
    configs.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
  }

  // ---- GET /logs?afterSeq= -------------------------------------------------------------------

  @Test
  void listLogs_returnsStoredLinesAfterSeq_inOrder() throws Exception {
    Fixture f = fixture(RUNNING);
    storeLogs(f.run(), 1, 5);

    mvc.perform(get(LOGS, f.run().getId()).param("afterSeq", "2").with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].seq").value(3))
        .andExpect(jsonPath("$[0].level").value("INFO"))
        .andExpect(jsonPath("$[0].step").doesNotExist())
        .andExpect(jsonPath("$[0].message").value("line 3"))
        .andExpect(jsonPath("$[2].seq").value(5));
  }

  @Test
  void listLogs_withoutAfterSeq_returnsFromTheStart() throws Exception {
    Fixture f = fixture(RUNNING);
    storeLogs(f.run(), 1, 3);

    mvc.perform(get(LOGS, f.run().getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].seq").value(1));
  }

  @Test
  void listLogs_isCappedAt5000Lines() throws Exception {
    Fixture f = fixture(RUNNING);
    storeLogs(f.run(), 1, 5_003);

    mvc.perform(get(LOGS, f.run().getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5_000))
        .andExpect(jsonPath("$[4999].seq").value(5_000));

    mvc.perform(get(LOGS, f.run().getId()).param("afterSeq", "5000").with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].seq").value(5_001));
  }

  @Test
  void listLogs_missingOrForeignRun_404() throws Exception {
    Fixture f = fixture(RUNNING);
    storeLogs(f.run(), 1, 2);
    User stranger =
        users.save(new User(null, 9, "stranger", null, null, "https://avatars.example.com/u/9"));

    mvc.perform(get(LOGS, "01MISSINGRUN0000000000000A").with(signedInAs(f.owner())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get(LOGS, f.run().getId()).with(signedInAs(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("run not found"));
  }

  // ---- GET /logs/stream ----------------------------------------------------------------------

  @Test
  void replaysStoredThenLive_inOrder_noDuplicates() throws Exception {
    Fixture f = fixture(RUNNING);
    String runId = f.run().getId();
    storeLogs(f.run(), 1, 5);

    MvcResult stream = open(f, null);
    awaitSubscribed(runId);
    publishLive(runId, 4, 8);

    awaitBody(stream, body -> seqs(body).size() >= 8);
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
  }

  @Test
  void lastEventId_resumesAfterIt() throws Exception {
    Fixture f = fixture(RUNNING);
    String runId = f.run().getId();
    storeLogs(f.run(), 1, 6);

    MvcResult stream = open(f, "3");
    awaitSubscribed(runId);
    publishLive(runId, 7, 7);

    awaitBody(stream, body -> seqs(body).size() >= 4);
    assertThat(seqs(body(stream))).containsExactly(4, 5, 6, 7);
    assertThat(parse(body(stream)).get(0).id()).isEqualTo("4");
  }

  @Test
  void closesOnDone() throws Exception {
    Fixture f = fixture(RUNNING);
    String runId = f.run().getId();
    MvcResult stream = open(f, null);
    awaitSubscribed(runId);

    publishLive(runId, 1, 2);
    redis.convertAndSend("run-logs:" + runId, "{\"type\":\"done\"}");

    awaitBody(stream, body -> body.contains("event:done"));
    List<Sse> events = parse(body(stream));
    assertThat(events.stream().map(Sse::event)).containsExactly("log", "log", "done");
    Sse done = events.get(2);
    assertThat(done.data()).isEqualTo("[DONE]");
    assertCompleted(stream);
  }

  @Test
  void terminalRun_replaysAndCloses() throws Exception {
    Fixture f = fixture(SUCCESS);
    storeLogs(f.run(), 1, 3);

    MvcResult stream = open(f, null);

    List<Sse> events = parse(body(stream));
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3);
    assertThat(events.get(events.size() - 1).event()).isEqualTo("done");
    assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
    assertCompleted(stream);
  }

  @Test
  void clientDisconnect_unsubscribes() throws Exception {
    Fixture f = fixture(RUNNING);
    String runId = f.run().getId();
    MvcResult stream = open(f, null);
    awaitSubscribed(runId);

    stream.getRequest().getAsyncContext().complete();

    awaitTrue(() -> subscriberCount(runId) == 0);
    assertThat(subscriberCount(runId)).isZero();
  }

  @Test
  void streamOfMissingRun_404() throws Exception {
    Fixture f = fixture(RUNNING);
    mvc.perform(get(STREAM, "01MISSINGRUN0000000000000A").with(signedInAs(f.owner())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---- helpers -------------------------------------------------------------------------------

  /** One SSE event: its id, name and data. Comment lines such as heartbeats are not events. */
  private record Sse(String id, String event, String data) {}

  private record Fixture(User owner, GenerationRun run) {}

  private Fixture fixture(RunStatus status) {
    Organization acme =
        organizations.save(new Organization(null, null, "vcs-acme", "acme", "acme", false, null));
    User owner = addMember(acme, 1, "owner", ADMIN);
    Project project =
        projects.save(
            new Project(
                null,
                acme.getId(),
                "acme/widgets",
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                owner.getId()));
    ProjectConfig config =
        configs.save(
            new ProjectConfig(
                null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, owner.getId()));
    GenerationRun run =
        new GenerationRun(
            null, project.getId(), config.getId(), MANUAL, false, BASE.plusSeconds(1));
    run.setStatus(status);
    return new Fixture(owner, generationRuns.saveAndFlush(run));
  }

  /** Stores lines {@code from..to} of the run, as the worker's flush does. */
  private void storeLogs(GenerationRun run, int from, int to) {
    List<GenerationRunLog> lines = new ArrayList<>();
    for (int seq = from; seq <= to; seq++) {
      lines.add(new GenerationRunLog(run.getId(), seq, ts(seq), "INFO", null, "line " + seq));
    }
    logs.saveAll(lines);
  }

  /**
   * Publishes lines {@code from..to} to the run's channel, as the worker does when it writes them.
   */
  private void publishLive(String runId, int from, int to) {
    for (int seq = from; seq <= to; seq++) {
      redis.convertAndSend(
          "run-logs:" + runId,
          "{\"seq\":"
              + seq
              + ",\"ts\":\""
              + ts(seq)
              + "\",\"level\":\"INFO\",\"step\":null,\"message\":\"line "
              + seq
              + "\"}");
    }
  }

  private MvcResult open(Fixture f, String lastEventId) throws Exception {
    var request = get(STREAM, f.run().getId()).with(signedInAs(f.owner()));
    if (lastEventId != null) {
      request.header("Last-Event-ID", lastEventId);
    }
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andReturn();
  }

  /** Waits until Redis has a subscriber on the run's channel, so a publish is not missed. */
  private void awaitSubscribed(String runId) throws InterruptedException {
    awaitTrue(() -> subscriberCount(runId) >= 1);
  }

  /** How many connections subscribe to the run's channel: PUBSUB NUMSUB, read through Lettuce. */
  private long subscriberCount(String runId) {
    return redis.execute(
        (RedisCallback<Long>)
            connection -> {
              RedisAsyncCommands<byte[], byte[]> lettuce =
                  (RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
              return lettuce
                  .pubsubNumsub(channel(runId).getBytes(UTF_8))
                  .toCompletableFuture()
                  .join()
                  .values()
                  .stream()
                  .findFirst()
                  .orElse(0L);
            });
  }

  private static String channel(String runId) {
    return "run-logs:" + runId;
  }

  private static String body(MvcResult stream) throws Exception {
    return stream.getResponse().getContentAsString(UTF_8);
  }

  private void awaitBody(MvcResult stream, java.util.function.Predicate<String> ready)
      throws Exception {
    awaitTrue(() -> {
      try {
        return ready.test(body(stream));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
  }

  /** The stream has ended: the emitter completed, so the async request has a result. */
  private static void assertCompleted(MvcResult stream) throws Exception {
    assertThat(stream.getAsyncResult(5_000)).isNull();
  }

  private static List<Sse> parse(String body) {
    List<Sse> events = new ArrayList<>();
    for (String block : body.split("\n\n")) {
      String id = null;
      String event = null;
      String data = null;
      for (String line : block.split("\n")) {
        if (line.startsWith("id:")) {
          id = line.substring(3).trim();
        } else if (line.startsWith("event:")) {
          event = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
          data = line.substring(5).trim();
        }
      }
      if (event != null) {
        events.add(new Sse(id, event, data));
      }
    }
    return events;
  }

  private static List<Integer> seqs(String body) {
    return parse(body).stream()
        .filter(e -> "log".equals(e.event()))
        .map(e -> Integer.parseInt(e.id()))
        .toList();
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static Instant ts(int seq) {
    return BASE.plusSeconds(seq);
  }

  /** Adds a user as a member of an org with the given role. */
  private User addMember(Organization org, long githubId, String login, Role role) {
    User user =
        users.save(
            new User(
                null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, RoleSource.SYNC));
    return user;
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }
}

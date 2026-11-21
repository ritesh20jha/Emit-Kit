package dev.emitkit.api.runs;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.RunStatus.RUNNING;
import static dev.emitkit.domain.RunStatus.SUCCESS;
import static dev.emitkit.domain.RunTrigger.MANUAL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** RUN-03: the stored log of a run and its live stream. Timings are cut down for the tests. */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET,
      "emitkit.logs.heartbeat=300ms",
      "emitkit.logs.gap-timeout=2s",
      "emitkit.logs.terminal-grace=3s"
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
  private static final String DONE = "{\"type\":\"done\"}";
  private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository generationRuns;
  @MockitoSpyBean private GenerationRunLogRepository logs;
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
  void listLogs_returnsStoredLinesAfterSeq_orAllWithoutIt() throws Exception {
    Fixture f = fixture(RUNNING, null);
    storeLogs(f.runId(), 1, 5);

    mvc.perform(get(LOGS, f.runId()).param("afterSeq", "2").with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].seq").value(3))
        .andExpect(jsonPath("$[0].message").value("line 3"));
    mvc.perform(get(LOGS, f.runId()).with(signedInAs(f.owner())))
        .andExpect(jsonPath("$.length()").value(5));
  }

  @Test
  void listLogs_isCappedAt5000Lines() throws Exception {
    Fixture f = fixture(RUNNING, null);
    storeLogs(f.runId(), 1, 5_003);

    mvc.perform(get(LOGS, f.runId()).with(signedInAs(f.owner())))
        .andExpect(jsonPath("$.length()").value(5_000));
    mvc.perform(get(LOGS, f.runId()).param("afterSeq", "5000").with(signedInAs(f.owner())))
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void listLogs_missingOrForeignRun_404() throws Exception {
    Fixture f = fixture(RUNNING, null);
    User stranger =
        users.save(new User(null, 9, "stranger", null, null, "https://avatars.example.com/u/9"));

    mvc.perform(get(LOGS, "01MISSINGRUN0000000000000A").with(signedInAs(f.owner())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get(LOGS, f.runId()).with(signedInAs(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("run not found"));
    mvc.perform(
            get(STREAM, "01MISSINGRUN0000000000000A")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .with(signedInAs(f.owner())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  // ---- GET /logs/stream ----------------------------------------------------------------------

  @Test
  void replaysStoredThenLive_inOrder_noDuplicates() throws Exception {
    Fixture f = fixture(RUNNING, null);
    storeLogs(f.runId(), 1, 5);

    MvcResult stream = open(f, null);
    awaitSubscribed(f.runId());
    publishLive(f.runId(), 4, 8);

    await(() -> seqs(body(stream)).size() >= 8);
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
  }

  /** Lines and done that arrive while the stored lines are still being read wait their turn. */
  @Test
  void linesAndDoneArrivingDuringReplay_areSentAfterIt() throws Exception {
    Fixture f = fixture(RUNNING, null);
    List<GenerationRunLog> stored = storeLogs(f.runId(), 1, 3);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    // The spy answers from the rows stored above: a repository proxy has no real method to call.
    doAnswer(
            invocation -> {
              if (first.getAndSet(false)) {
                entered.countDown();
                release.await();
              }
              int after = invocation.getArgument(1);
              return stored.stream().filter(line -> line.getSeq() > after).toList();
            })
        .when(logs)
        .findByRunIdAndSeqGreaterThanOrderBySeq(eq(f.runId()), anyInt(), any(Pageable.class));

    MvcResult stream = open(f, null);
    assertThat(entered.await(5, SECONDS)).isTrue();
    awaitSubscribed(f.runId());
    publishLive(f.runId(), 4, 6);
    redis.convertAndSend(channel(f.runId()), DONE);
    release.countDown();

    await(() -> body(stream).contains("event:done"));
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3, 4, 5, 6);
    assertCompleted(stream);
  }

  @Test
  void lastEventId_resumesAfterIt() throws Exception {
    Fixture f = fixture(RUNNING, null);
    storeLogs(f.runId(), 1, 6);

    MvcResult stream = open(f, "3");
    awaitSubscribed(f.runId());
    publishLive(f.runId(), 7, 7);

    await(() -> seqs(body(stream)).size() >= 4);
    assertThat(seqs(body(stream))).containsExactly(4, 5, 6, 7);
    assertThat(events(body(stream)).get(0).id()).isEqualTo("4");
  }

  @Test
  void closesOnDone() throws Exception {
    Fixture f = fixture(RUNNING, null);
    MvcResult stream = open(f, null);
    awaitSubscribed(f.runId());

    publishLive(f.runId(), 1, 2);
    redis.convertAndSend(channel(f.runId()), DONE);

    await(() -> body(stream).contains("event:done"));
    List<Sse> events = events(body(stream));
    assertThat(events.stream().map(Sse::event)).containsExactly("log", "log", "done");
    assertThat(events.get(2).data()).isEqualTo("[DONE]");
    assertCompleted(stream);
  }

  /** A run that ended long ago has nothing more to come: replay, done, and the stream closes. */
  @Test
  void terminalRun_replaysAndCloses() throws Exception {
    Fixture f = fixture(SUCCESS, BASE);
    storeLogs(f.runId(), 1, 3);

    MvcResult stream = open(f, null);

    await(() -> body(stream).contains("event:done"));
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3);
    assertThat(events(body(stream)).getLast().data()).isEqualTo("[DONE]");
    assertCompleted(stream);
  }

  /** A run that has just finished may still be saving lines: they arrive before done. */
  @Test
  void freshlyFinishedRun_deliversLateLinesBeforeDone() throws Exception {
    Fixture f = fixture(SUCCESS, Instant.now());
    storeLogs(f.runId(), 1, 2);

    MvcResult stream = open(f, null);
    awaitSubscribed(f.runId());
    publishLive(f.runId(), 3, 3);
    await(() -> seqs(body(stream)).size() >= 3);
    redis.convertAndSend(channel(f.runId()), DONE);

    await(() -> body(stream).contains("event:done"));
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3);
    assertCompleted(stream);
  }

  /** The run finishes while the stream is open and its done message never comes. */
  @Test
  void runFinishingWithoutDone_endsOnStatusCheck() throws Exception {
    Fixture f = fixture(RUNNING, null);
    storeLogs(f.runId(), 1, 2);
    MvcResult stream = open(f, null);
    awaitSubscribed(f.runId());

    GenerationRun run = generationRuns.findById(f.runId()).orElseThrow();
    run.setStatus(SUCCESS);
    run.setFinishedAt(Instant.now());
    generationRuns.saveAndFlush(run);

    await(() -> body(stream).contains("event:done"));
    assertThat(seqs(body(stream))).containsExactly(1, 2);
    assertCompleted(stream);
  }

  /** A seq gap waits for the worker's flush; a gap never filled is sent past after the wait. */
  @Test
  void gapInLive_isFilledFromTheDatabase_orSentPastAfterTheWait() throws Exception {
    Fixture f = fixture(RUNNING, null);
    storeLogs(f.runId(), 1, 3);

    MvcResult stream = open(f, null);
    awaitSubscribed(f.runId());
    publishLive(f.runId(), 5, 5);
    Thread.sleep(300);
    storeLogs(f.runId(), 4, 4);
    await(() -> seqs(body(stream)).size() >= 5);
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3, 4, 5);

    publishLive(f.runId(), 7, 7);
    await(() -> seqs(body(stream)).size() >= 6);
    assertThat(seqs(body(stream))).containsExactly(1, 2, 3, 4, 5, 7);
  }

  @Test
  void clientDisconnect_unsubscribes() throws Exception {
    Fixture f = fixture(RUNNING, null);
    MvcResult stream = open(f, null);
    awaitSubscribed(f.runId());

    stream.getRequest().getAsyncContext().complete();

    await(() -> subscriberCount(f.runId()) == 0);
  }

  // ---- helpers -------------------------------------------------------------------------------

  /** One SSE event: its id, name and data. Comment lines such as heartbeats are not events. */
  private record Sse(String id, String event, String data) {}

  private record Fixture(User owner, String runId) {}

  private Fixture fixture(RunStatus status, @Nullable Instant finishedAt) {
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
    run.setFinishedAt(finishedAt);
    return new Fixture(owner, generationRuns.saveAndFlush(run).getId());
  }

  private List<GenerationRunLog> storeLogs(String runId, int from, int to) {
    List<GenerationRunLog> lines = new ArrayList<>();
    for (int seq = from; seq <= to; seq++) {
      lines.add(new GenerationRunLog(runId, seq, ts(seq), "INFO", null, "line " + seq));
    }
    return logs.saveAll(lines);
  }

  /** Publishes lines {@code from..to} to the run's channel, as the worker does. */
  private void publishLive(String runId, int from, int to) {
    for (int seq = from; seq <= to; seq++) {
      redis.convertAndSend(
          channel(runId),
          "{\"seq\":%d,\"ts\":\"%s\",\"level\":\"INFO\",\"step\":null,\"message\":\"line %d\"}"
              .formatted(seq, ts(seq), seq));
    }
  }

  private MvcResult open(Fixture f, @Nullable String lastEventId) throws Exception {
    var request = get(STREAM, f.runId()).with(signedInAs(f.owner()));
    if (lastEventId != null) {
      request.header("Last-Event-ID", lastEventId);
    }
    return mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
  }

  /** Waits until Redis has a subscriber on the run's channel, so a publish is not missed. */
  private void awaitSubscribed(String runId) throws InterruptedException {
    await(() -> subscriberCount(runId) >= 1);
  }

  /** PUBSUB NUMSUB for the run's channel. Spring's execute() cannot read a multi-bulk reply. */
  private long subscriberCount(String runId) {
    return redis.execute(
        (RedisCallback<Long>)
            connection -> {
              @SuppressWarnings("unchecked")
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

  private static String body(MvcResult stream) {
    return new String(stream.getResponse().getContentAsByteArray(), UTF_8);
  }

  /** The stream has ended: the emitter completed, so the async request has a result. */
  private static void assertCompleted(MvcResult stream) throws Exception {
    assertThat(stream.getAsyncResult(5_000)).isNull();
  }

  private static List<Sse> events(String body) {
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
    return events(body).stream()
        .filter(e -> "log".equals(e.event()))
        .map(e -> Integer.parseInt(e.id()))
        .toList();
  }

  /** Waits up to ten seconds for the condition, and fails if it never holds. */
  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static Instant ts(int seq) {
    return BASE.plusSeconds(seq);
  }

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

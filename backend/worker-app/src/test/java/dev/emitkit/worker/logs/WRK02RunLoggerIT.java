package dev.emitkit.worker.logs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.worker.pipeline.RunLogger;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Run logs against PostgreSQL and Redis: lines reach the database and the run's channel. */
@SpringBootTest(
    classes = WRK02RunLoggerIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "emitkit.vault.cipher=local",
      "emitkit.worker.pipeline.enabled=true"
    })
@Testcontainers
class WRK02RunLoggerIT {

  private static final String DONE = "{\"type\":\"done\"}";
  private static final Instant EPOCH = Instant.parse("2026-11-01T10:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicLong SEQUENCE = new AtomicLong();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private GenerationRunLogRepository logs;
  @Autowired private RunLoggerFactory factory;
  @Autowired private StringRedisTemplate redis;

  private final List<RunLogger> opened = new ArrayList<>();
  private final List<String> received = new CopyOnWriteArrayList<>();

  /** While set, the repository proxy from {@link #flakyRepo()} fails every save. */
  private final AtomicBoolean failSaves = new AtomicBoolean();

  private String runId;
  private RedisClient redisClient;
  private StatefulRedisPubSubConnection<String, String> subscriber;

  @BeforeEach
  void seed() {
    // Each test gets its own user, organization and project: the schema makes those unique.
    long n = SEQUENCE.incrementAndGet();
    User creator =
        users.saveAndFlush(
            new User(null, n, "user" + n, "User " + n, "user" + n + "@example.com", "https://a"));
    Organization org =
        orgs.saveAndFlush(
            new Organization(null, "github", "org-" + n, "org" + n, "Org " + n, false, null));
    Project project =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                "acme/api-" + n,
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                creator.getId()));
    ProjectConfig config =
        configs.saveAndFlush(
            new ProjectConfig(
                null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId()));
    runId =
        runs.saveAndFlush(
                new GenerationRun(
                    null, project.getId(), config.getId(), RunTrigger.MANUAL, false, EPOCH))
            .getId();

    // Subscribed before any line is written, so no message is missed.
    redisClient = RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
    subscriber = redisClient.connectPubSub();
    subscriber.addListener(
        new RedisPubSubAdapter<String, String>() {
          @Override
          public void message(String channel, String message) {
            received.add(message);
          }
        });
    subscriber.sync().subscribe("run-logs:" + runId);
  }

  @AfterEach
  void tearDown() {
    failSaves.set(false);
    opened.forEach(RunLogger::done);
    subscriber.close();
    redisClient.shutdown();
  }

  @Test
  void seqStrictlyIncreasing_andResumesFromDb() {
    RunLogger first = open();
    first.info("one");
    first.info("two");
    first.flush();

    RunLogger second = open();
    second.info("three");
    second.warn("four");
    second.flush();

    assertThat(storedRows()).extracting(GenerationRunLog::getSeq).containsExactly(1, 2, 3, 4);
  }

  @Test
  void linesPublishedToRedis() {
    RunLogger log = open();

    log.info("hello");

    await().atMost(5, SECONDS).until(() -> !received.isEmpty());
    LogLineMessage line = JSON.readValue(received.getFirst(), LogLineMessage.class);
    assertThat(line.message()).isEqualTo("hello");
    assertThat(line.level()).isEqualTo("INFO");
    assertThat(line.seq()).isEqualTo(1);
  }

  @Test
  void flushedOnStepChange() {
    RunLogger log = open();
    log.info("before");

    log.step(StepName.PARSE_SPEC);

    assertThat(stored()).containsExactly("before");
    assertThat(storedRows().getFirst().getStep()).isNull();
  }

  @Test
  void flushedEveryTwoSeconds() {
    RunLogger log = open();
    log.info("tick");

    await().atMost(10, SECONDS).until(() -> stored().contains("tick"));
  }

  @Test
  void donePublishesTerminalMessage() {
    RunLogger log = open();
    log.info("last line");

    log.done();

    await().atMost(5, SECONDS).until(() -> !received.isEmpty() && received.getLast().equals(DONE));
    assertThat(received()).last().isEqualTo("{\"type\":\"done\"}");
    assertThat(stored()).containsExactly("last line");
  }

  @Test
  void maskedValueNeverStoredOrPublished() {
    RunLogger log = open();
    log.mask("sk-secret");

    log.info("key=sk-secret");
    log.info("after");
    log.flush();

    // Redis delivers in publish order, so once "after" arrives the masked line has arrived too.
    await().atMost(5, SECONDS).until(() -> received.stream().anyMatch(m -> m.contains("after")));
    assertThat(stored()).noneMatch(l -> l.contains("sk-secret")).contains("key=****", "after");
    assertThat(received()).noneMatch(m -> m.contains("sk-secret"));
  }

  @Test
  void doneRetriedAfterFailedFlushSavesLinesBeforeDone() {
    RunLogger log = openDirect(flakyRepo(), 100);
    failSaves.set(true);
    log.info("x");

    assertThatThrownBy(log::done).isInstanceOf(DataAccessResourceFailureException.class);
    // Redis delivers in publish order: once "y" arrives, a DONE published during the failed
    // done() would already have arrived too.
    log.info("y");
    await().atMost(5, SECONDS).until(() -> received.stream().anyMatch(m -> m.contains("\"y\"")));
    assertThat(received()).noneMatch(DONE::equals);

    failSaves.set(false);
    log.done();

    await().atMost(5, SECONDS).until(() -> !received.isEmpty() && received.getLast().equals(DONE));
    assertThat(stored()).containsExactly("x", "y");
  }

  @Test
  void nulCharacterIsStrippedBeforeSaving() {
    RunLogger log = open();

    log.info("a\u0000b");
    log.flush();

    assertThat(stored()).containsExactly("ab");
  }

  @Test
  void rejectedLineDoesNotBlockLaterLines() {
    // An older logger left seq 1 behind: the new logger's first line collides with it.
    logs.saveAndFlush(new GenerationRunLog(runId, 1, EPOCH, "INFO", null, "existing"));
    RunLogger log = openDirect(logs, 100);

    log.info("collides");
    log.info("later");
    log.flush();

    assertThat(stored()).containsExactly("existing", "later");
  }

  @Test
  void bufferKeepsNewestLinesWhileDatabaseIsDown() {
    RunLogger log = openDirect(flakyRepo(), 3);
    failSaves.set(true);

    for (int i = 1; i <= 5; i++) {
      log.info("line" + i);
    }
    failSaves.set(false);
    log.flush();

    assertThat(stored()).containsExactly("line3", "line4", "line5");
  }

  private RunLogger open() {
    RunLogger log = factory.create(runId);
    opened.add(log);
    return log;
  }

  private RunLogger openDirect(GenerationRunLogRepository repository, int maxBufferedLines) {
    RunLogger log = new RedisRunLogger(runId, 0, repository, redis, maxBufferedLines);
    opened.add(log);
    return log;
  }

  /** The real repository, except that saves fail while {@link #failSaves} is set. */
  private GenerationRunLogRepository flakyRepo() {
    return (GenerationRunLogRepository)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {GenerationRunLogRepository.class},
            (proxy, method, args) -> {
              boolean save = method.getName().equals("save") || method.getName().equals("saveAll");
              if (save && failSaves.get()) {
                throw new DataAccessResourceFailureException("database down");
              }
              try {
                return method.invoke(logs, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** Message texts of the run's stored lines, in seq order. */
  private List<String> stored() {
    return storedRows().stream().map(GenerationRunLog::getMessage).toList();
  }

  private List<GenerationRunLog> storedRows() {
    return logs.findByRunIdAndSeqGreaterThanOrderBySeq(runId, 0);
  }

  /** Every message published to the run's channel so far, in order. */
  private List<String> received() {
    return new ArrayList<>(received);
  }

  /** Boots JPA and Flyway against the containers, plus the factory under test. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  @Import(RunLoggerFactory.class)
  static class TestApp {}
}

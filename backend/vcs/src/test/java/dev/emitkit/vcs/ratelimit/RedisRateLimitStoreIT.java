package dev.emitkit.vcs.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.emitkit.vcs.RateLimit;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisRateLimitStoreIT {

  private static final String USER = "octocat";
  private static final String KEY = "ratelimit:" + USER;

  @Container
  static GenericContainer<?> redisContainer =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate redis;

  private RedisRateLimitStore store;

  @BeforeAll
  static void connect() {
    connectionFactory =
        new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redis = new StringRedisTemplate(connectionFactory);
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      connection.serverCommands().flushDb();
    }
    store = new RedisRateLimitStore(redis);
  }

  @Test
  void constructor_rejectsNullTemplate() {
    assertThatThrownBy(() -> new RedisRateLimitStore(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void recordThenGet_returnsTheReadingWithLimitNotTracked() {
    Instant resetAt = futureReset().plusNanos(123_456_789);

    store.record(USER, 42, resetAt);

    assertThat(store.get(USER)).contains(new RateLimit(0, 42, resetAt));
    assertThat(redis.hasKey(KEY)).isTrue();
    assertThat(redis.type(KEY)).isEqualTo(DataType.HASH);
    assertThat(redis.<String, String>opsForHash().entries(KEY))
        .containsOnlyKeys("remaining", "resetAt");
  }

  @Test
  void record_expiresOneMinuteAfterReset() {
    Instant resetAt = futureReset();

    store.record(USER, 42, resetAt);

    long expectedMillis = Duration.between(Instant.now(), resetAt.plusSeconds(60)).toMillis();
    assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS))
        .isCloseTo(expectedMillis, within(5_000L));
  }

  @Test
  void get_absentUser_isEmpty() {
    assertThat(store.get(USER)).isEmpty();
  }

  @Test
  void record_overwritesThePreviousReading() {
    store.record(USER, 42, futureReset());
    Instant laterReset = futureReset().plusSeconds(3_600);

    store.record(USER, 7, laterReset);

    assertThat(store.get(USER)).contains(new RateLimit(0, 7, laterReset));
  }

  @Test
  void record_resetInThePastBeyondGrace_removesKeyAndWritesNothing() {
    store.record(USER, 42, futureReset());

    store.record(USER, 0, Instant.now().minusSeconds(120));

    assertThat(redis.hasKey(KEY)).isFalse();
    assertThat(store.get(USER)).isEmpty();
  }

  @Test
  void get_malformedHash_isEmptyRatherThanThrowing() {
    redis.opsForHash().putAll(KEY, Map.of("remaining", "lots", "resetAt", "soon"));
    assertThat(store.get(USER)).isEmpty();

    redis.opsForHash().delete(KEY, "resetAt");
    redis.opsForHash().put(KEY, "remaining", "5");
    assertThat(store.get(USER)).isEmpty();
  }

  @Test
  void wrongTypeKey_getAndRecordFailOpen() {
    redis.opsForValue().set("ratelimit:u1", "plain");

    assertThat(store.get("u1")).isEmpty();
    assertThatCode(() -> store.record("u1", 1, futureReset())).doesNotThrowAnyException();
  }

  @Test
  void redisUnreachable_getAndRecordFailOpen() throws IOException {
    LettuceConnectionFactory down = new LettuceConnectionFactory("127.0.0.1", closedPort());
    down.afterPropertiesSet();
    try {
      RedisRateLimitStore offline = new RedisRateLimitStore(new StringRedisTemplate(down));

      assertThat(offline.get(USER)).isEmpty();
      assertThatCode(() -> offline.record(USER, 1, futureReset())).doesNotThrowAnyException();
      assertThatCode(() -> offline.record(USER, 0, Instant.now().minusSeconds(120)))
          .doesNotThrowAnyException();
    } finally {
      down.destroy();
    }
  }

  /** A port that was free a moment ago, so nothing listens on it. */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static Instant futureReset() {
    return Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
  }
}

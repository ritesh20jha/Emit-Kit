package dev.emitkit.vcs.ratelimit;

import static dev.emitkit.vcs.ratelimit.RateLimitPolicy.BACKGROUND;
import static dev.emitkit.vcs.ratelimit.RateLimitPolicy.INTERACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.TransientException;
import dev.emitkit.vcs.RateLimit;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.error.VcsRateLimitException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RateLimitedVcsClientTest {

  private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

  private InMemoryVcsClient fake;
  private InMemoryRateLimitStore store;
  private RecordingSleeper sleeper;
  private Clock clock;

  @BeforeEach
  void setUp() {
    fake = new InMemoryVcsClient();
    store = new InMemoryRateLimitStore();
    sleeper = new RecordingSleeper();
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private VcsClient client(RateLimitPolicy policy) {
    return new RateLimitedVcsClient(fake, "u1", store, policy, clock, sleeper);
  }

  @Test
  void interactive_lowQuota_failsFast() {
    Instant resetAt = clock.instant().plusSeconds(600);
    store.record("u1", 99, resetAt);

    assertThatThrownBy(() -> client(INTERACTIVE).listRepos())
        .isInstanceOfSatisfying(
            VcsRateLimitException.class, e -> assertThat(e.resetAt()).isEqualTo(resetAt));
    assertThat(fake.calls()).isEmpty();
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @Test
  void background_lowQuota_resetSoon_waitsThenCalls() {
    store.record("u1", 99, clock.instant().plusSeconds(2));

    client(BACKGROUND).listRepos();

    assertThat(sleeper.slept()).isEqualTo(Duration.ofSeconds(2));
    assertThat(fake.calls()).hasSize(1);
  }

  @Test
  void background_lowQuota_resetExactlyFiveMinutesAway_waits() {
    store.record("u1", 99, clock.instant().plus(Duration.ofMinutes(5)));

    client(BACKGROUND).listRepos();

    assertThat(sleeper.slept()).isEqualTo(Duration.ofMinutes(5));
    assertThat(fake.calls()).hasSize(1);
  }

  @Test
  void background_lowQuota_resetFar_throwsTransient() {
    store.record("u1", 99, clock.instant().plusSeconds(600));

    assertThatThrownBy(() -> client(BACKGROUND).listRepos())
        .isInstanceOf(VcsRateLimitException.class)
        .isInstanceOf(TransientException.class);
    assertThat(fake.calls()).isEmpty();
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(RateLimitPolicy.class)
  void enoughQuota_callsThrough(RateLimitPolicy policy) {
    // 100 is the threshold itself: only strictly fewer than 100 requests left counts as low.
    store.record("u1", 100, clock.instant().plusSeconds(600));

    client(policy).listRepos();

    assertThat(fake.calls()).hasSize(1);
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(RateLimitPolicy.class)
  void noStoredValue_callsThrough(RateLimitPolicy policy) {
    client(policy).listRepos();

    assertThat(fake.calls()).hasSize(1);
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(RateLimitPolicy.class)
  void resetInPast_ignoresStoredValue(RateLimitPolicy policy) {
    store.record("u1", 0, clock.instant().minusSeconds(1));

    client(policy).listRepos();

    assertThat(fake.calls()).hasSize(1);
    assertThat(sleeper.sleeps()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(RateLimitPolicy.class)
  void rateLimit_isNotGuarded(RateLimitPolicy policy) {
    store.record("u1", 0, clock.instant().plusSeconds(600));

    // The fake's own quota is non-zero; the stored remaining of 0 must not be returned.
    assertThat(client(policy).rateLimit().remaining()).isNotZero();
    assertThat(fake.calls("rateLimit")).hasSize(1);
  }

  @Test
  void everyMethodExceptRateLimit_isGuarded() {
    store.record("u1", 0, clock.instant().plusSeconds(600));
    VcsClient client = client(INTERACTIVE);
    List<ThrowingCallable> calls =
        List.of(
            client::listOrgs,
            client::listRepos,
            () -> client.createRepo("acme", "api", false),
            () -> client.getFile("acme/api", "f", "main"),
            () -> client.putFile("acme/api", "f", "x", null, "m"),
            () -> client.createBranch("acme/api", "b", "sha"),
            () -> client.branchHead("acme/api", "main"),
            () -> client.deleteBranch("acme/api", "b"),
            () -> client.listBlobShas("acme/api", "main", ""),
            () -> client.commitTree("acme/api", "main", List.of(), "m"),
            () -> client.openPr("acme/api", "h", "main", "t", "b"),
            () -> client.comment("acme/api", 1, "c"),
            () -> client.createTag("acme/api", "v1", "sha"),
            () -> client.registerWebhook("acme/api", "https://h", "s", Set.of("push")),
            () -> client.deleteWebhook("acme/api", 1L));
    for (ThrowingCallable call : calls) {
      assertThatThrownBy(call).isInstanceOf(VcsRateLimitException.class);
    }
    assertThat(fake.calls()).isEmpty();
  }

  /** Holds the last reading per user, with limit 0 as the store contract allows. */
  private static final class InMemoryRateLimitStore implements RateLimitStore {

    private final Map<String, RateLimit> quotas = new HashMap<>();

    @Override
    public void record(String userId, int remaining, Instant resetAt) {
      quotas.put(userId, new RateLimit(0, remaining, resetAt));
    }

    @Override
    public Optional<RateLimit> get(String userId) {
      return Optional.ofNullable(quotas.get(userId));
    }
  }

  /** Records every sleep instead of waiting. */
  private static final class RecordingSleeper implements Sleeper {

    private final List<Duration> sleeps = new ArrayList<>();

    @Override
    public void sleep(Duration duration) {
      sleeps.add(duration);
    }

    /** The sleeps so far, in order. */
    List<Duration> sleeps() {
      return List.copyOf(sleeps);
    }

    /** The one duration slept. Fails unless exactly one sleep happened. */
    Duration slept() {
      if (sleeps.size() != 1) {
        throw new AssertionError("expected exactly one sleep but was " + sleeps);
      }
      return sleeps.get(0);
    }
  }
}

package dev.emitkit.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QueuesTest {

  @Test
  void retryQueueForMapsEachRetryAttemptToItsDelayQueue() {
    assertThat(Queues.retryQueueFor(1)).isEqualTo("generation.retry.10s");
    assertThat(Queues.retryQueueFor(2)).isEqualTo("generation.retry.60s");
    assertThat(Queues.retryQueueFor(3)).isEqualTo("generation.retry.300s");
  }

  @Test
  void retryQueueForRejectsAttemptZero() {
    assertThatThrownBy(() -> Queues.retryQueueFor(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retryQueueForRejectsAttemptBeyondMaxAttempts() {
    assertThatThrownBy(() -> Queues.retryQueueFor(4)).isInstanceOf(IllegalArgumentException.class);
  }
}

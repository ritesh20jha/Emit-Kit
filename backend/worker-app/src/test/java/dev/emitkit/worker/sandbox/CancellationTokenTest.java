package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

  @Test
  void callbacksRunOnceAndOnlyOnCancel() {
    CancellationToken token = new CancellationToken();
    AtomicInteger ran = new AtomicInteger();
    token.onCancel(ran::incrementAndGet);
    assertThat(ran).hasValue(0);

    token.cancel();
    token.cancel();

    assertThat(token.isCancelled()).isTrue();
    assertThat(ran).hasValue(1);
  }

  @Test
  void onCancelRunsImmediatelyWhenAlreadyCancelled() {
    CancellationToken token = new CancellationToken();
    token.cancel();
    AtomicInteger ran = new AtomicInteger();

    token.onCancel(ran::incrementAndGet);

    assertThat(ran).hasValue(1);
  }

  @Test
  void failingCallbackDoesNotStopTheOthers() {
    CancellationToken token = new CancellationToken();
    AtomicInteger ran = new AtomicInteger();
    token.onCancel(() -> {
      throw new IllegalStateException("boom");
    });
    token.onCancel(ran::incrementAndGet);

    token.cancel();

    assertThat(ran).hasValue(1);
  }
}

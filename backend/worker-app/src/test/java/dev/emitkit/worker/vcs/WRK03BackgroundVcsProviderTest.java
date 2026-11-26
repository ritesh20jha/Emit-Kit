package dev.emitkit.worker.vcs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WRK03BackgroundVcsProviderTest {

  private final List<String> calls = new ArrayList<>();
  private final VcsProvider delegate =
      (VcsProvider)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {VcsProvider.class},
              (proxy, method, args) -> {
                calls.add(method.getName() + (args == null ? "" : Arrays.toString(args)));
                return null;
              });

  @Test
  void forProjectWithoutPolicy_usesBackgroundPolicy() {
    new BackgroundVcsProvider(delegate).forProject("creator-1");

    assertThat(calls).containsExactly("forProject[creator-1, BACKGROUND]");
  }

  @Test
  void forProjectWithPolicy_isPassedThroughUnchanged() {
    new BackgroundVcsProvider(delegate).forProject("creator-1", RateLimitPolicy.INTERACTIVE);

    assertThat(calls).containsExactly("forProject[creator-1, INTERACTIVE]");
  }
}

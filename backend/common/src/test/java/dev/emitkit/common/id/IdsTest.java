package dev.emitkit.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdsTest {

  @Test
  void ulidIs26CrockfordChars() {
    assertThat(Ids.newUlid()).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
  }

  @Test
  void idsAreMonotonicWithinAMillisecond() {
    var ids = IntStream.range(0, 1000).mapToObj(i -> Ids.newUlid()).toList();
    assertThat(ids).isSorted().doesNotHaveDuplicates();
  }
}

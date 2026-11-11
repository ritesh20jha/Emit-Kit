package dev.emitkit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunStatusTest {

  @ParameterizedTest
  @EnumSource(
      value = RunStatus.class,
      names = {"SUCCESS", "FAILED", "CANCELLED"})
  void endStatesAreTerminal(RunStatus status) {
    assertThat(status.isTerminal()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = RunStatus.class,
      names = {"QUEUED", "RUNNING", "AWAITING_APPROVAL"})
  void inFlightStatesAreNotTerminal(RunStatus status) {
    assertThat(status.isTerminal()).isFalse();
  }
}

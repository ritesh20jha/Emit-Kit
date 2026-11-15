package dev.emitkit.api.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.emitkit.api.runs.StepState.StepStatus;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.RunApprovalRepository;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.StepSpan;
import dev.emitkit.domain.UserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The step states of a run, from its status, its current step and which steps logged. */
class RunMapperStepsTest {

  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

  private final GenerationRunLogRepository logs = mock(GenerationRunLogRepository.class);
  private final RunMapper mapper =
      new RunMapper(
          logs,
          mock(LintResultRepository.class),
          mock(RunApprovalRepository.class),
          mock(RunArtifactRepository.class),
          mock(UserRepository.class));

  /**
   * Columns: run status, current step, logged steps ("ALL" = every step before the current one,
   * "NONE" = no step), dry run, then the expected state of all eleven steps in pipeline order.
   */
  @ParameterizedTest(name = "{0} at {1}, logged {2}, dryRun {3}")
  @CsvSource(
      delimiter = '|',
      value = {
        "RUNNING | RUN_GENERATORS | FETCH_SPEC,PARSE_SPEC | false | DONE,DONE,SKIPPED,SKIPPED,SKIPPED,SKIPPED,RUNNING,PENDING,PENDING,PENDING,PENDING",
        "RUNNING | RUN_GENERATORS | ALL | false | DONE,DONE,DONE,DONE,DONE,DONE,RUNNING,PENDING,PENDING,PENDING,PENDING",
        "FAILED | RUN_GENERATORS | ALL | false | DONE,DONE,DONE,DONE,DONE,DONE,FAILED,PENDING,PENDING,PENDING,PENDING",
        "CANCELLED | RUN_GENERATORS | ALL | false | DONE,DONE,DONE,DONE,DONE,DONE,FAILED,PENDING,PENDING,PENDING,PENDING",
        "SUCCESS | FINALIZE | NONE | false | DONE,DONE,DONE,DONE,DONE,DONE,DONE,DONE,DONE,DONE,DONE",
        "SUCCESS | FINALIZE | NONE | true | DONE,DONE,DONE,DONE,DONE,DONE,DONE,DONE,DONE,SKIPPED,DONE",
        "QUEUED | | NONE | false | PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,PENDING,PENDING"
      })
  void stepStates(
      RunStatus status, StepName current, String logged, boolean dryRun, String expected) {
    GenerationRun run = new GenerationRun(null, "project", "config", RunTrigger.MANUAL, dryRun, T0);
    run.setStatus(status);
    run.setCurrentStep(current);
    when(logs.findStepSpans(run.getId())).thenReturn(spans(logged, current));

    List<StepStatus> states = mapper.toDetail(run).steps().stream().map(StepState::status).toList();

    assertThat(states)
        .containsExactly(
            Arrays.stream(expected.split(",")).map(StepStatus::valueOf).toArray(StepStatus[]::new));
  }

  private static List<StepSpan> spans(String logged, StepName current) {
    return switch (logged) {
      case "ALL" ->
          Arrays.stream(StepName.values())
              .filter(step -> step.ordinal() < current.ordinal())
              .map(step -> new StepSpan(step, T0, T0))
              .toList();
      case "NONE" -> List.of();
      default ->
          Arrays.stream(logged.split(","))
              .map(step -> new StepSpan(StepName.valueOf(step), T0, T0))
              .toList();
    };
  }
}

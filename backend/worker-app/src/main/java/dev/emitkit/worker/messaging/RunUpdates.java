package dev.emitkit.worker.messaging;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes to a run only while it is still running, so a finished run is never written again. */
final class RunUpdates {

  private RunUpdates() {}

  /**
   * Applies {@code change} to the run in one transaction, on its locked row. Returns false, writing
   * nothing, if the run is missing or already terminal.
   */
  static boolean ifRunning(
      TransactionTemplate transactions,
      GenerationRunRepository runs,
      String runId,
      Consumer<GenerationRun> change) {
    Boolean applied =
        transactions.execute(tx ->
            runs.findByIdForUpdate(runId)
                .filter(run -> !run.getStatus().isTerminal())
                .map(run -> {
                  change.accept(run);
                  return true;
                })
                .orElse(false));
    return Boolean.TRUE.equals(applied);
  }
}

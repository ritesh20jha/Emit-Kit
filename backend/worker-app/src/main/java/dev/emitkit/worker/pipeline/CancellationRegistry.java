package dev.emitkit.worker.pipeline;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs asked to stop: filled by {@link #add}, and checked against {@code cancel_requested} at most
 * once every five seconds per run, so a cancel sent to another worker still counts.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class CancellationRegistry {

  static final Duration DB_RECHECK = Duration.ofSeconds(5);

  private final GenerationRunRepository runs;
  private final InstantSource clock;
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
  private final Map<String, Instant> lastDbCheck = new ConcurrentHashMap<>();

  @Autowired
  public CancellationRegistry(GenerationRunRepository runs) {
    this(runs, InstantSource.system());
  }

  CancellationRegistry(GenerationRunRepository runs, InstantSource clock) {
    this.runs = runs;
    this.clock = clock;
  }

  /** Records that the run has been asked to stop. */
  public void add(String runId) {
    cancelled.add(runId);
  }

  /**
   * Throws {@link CancelledException} if the run has been asked to stop, either recorded here or
   * flagged in the database.
   */
  public void checkCancelled(String runId) {
    if (cancelled.contains(runId) || requestedInDatabase(runId)) {
      throw new CancelledException(runId);
    }
  }

  /** Forgets the run. The runner calls it when it stops working on the run. */
  public void release(String runId) {
    cancelled.remove(runId);
    lastDbCheck.remove(runId);
  }

  private boolean requestedInDatabase(String runId) {
    Instant now = clock.instant();
    Instant last = lastDbCheck.get(runId);
    if (last != null && Duration.between(last, now).compareTo(DB_RECHECK) < 0) {
      return false;
    }
    lastDbCheck.put(runId, now);
    boolean requested = runs.findById(runId).map(GenerationRun::isCancelRequested).orElse(false);
    if (requested) {
      cancelled.add(runId);
    }
    return requested;
  }
}

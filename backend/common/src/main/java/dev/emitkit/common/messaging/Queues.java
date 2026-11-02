package dev.emitkit.common.messaging;

import java.util.List;

/** Names of the RabbitMQ exchanges, queues and routing keys used by the job pipeline. */
public final class Queues {

  /** Direct exchange that carries generation jobs. */
  public static final String JOBS_EXCHANGE = "emitkit.jobs";

  /** Queue consumed by workers. */
  public static final String JOBS_QUEUE = "generation.jobs";

  /** Routing key that binds {@link #JOBS_QUEUE} to {@link #JOBS_EXCHANGE}. */
  public static final String JOBS_ROUTING_KEY = "generation";

  /** Retry queues in attempt order: index 0 holds attempt 1, and so on. */
  public static final List<String> RETRY_QUEUES =
      List.of("generation.retry.10s", "generation.retry.60s", "generation.retry.300s");

  /** Dead-letter queue for jobs that exhaust their attempts. */
  public static final String DLQ = "generation.dlq";

  /** Fanout exchange that delivers control commands to every worker. */
  public static final String CONTROL_EXCHANGE = "emitkit.control";

  /** Number of attempts a job gets before it is dead-lettered. */
  public static final int MAX_ATTEMPTS = 3;

  private Queues() {}

  /**
   * Returns the retry queue to park a job in after a failed attempt: attempt 1 maps to the 10 s
   * queue, attempt 2 to 60 s, and attempt 3 to 300 s.
   *
   * @param attempt the attempt number of the retry being scheduled (failed attempt + 1), from 1 to
   *     {@link #MAX_ATTEMPTS}
   * @return the retry queue name
   * @throws IllegalArgumentException if {@code attempt} is outside 1 to {@link #MAX_ATTEMPTS}
   */
  public static String retryQueueFor(int attempt) {
    if (attempt < 1 || attempt > MAX_ATTEMPTS) {
      throw new IllegalArgumentException(
          "attempt must be between 1 and " + MAX_ATTEMPTS + ", was " + attempt);
    }
    return RETRY_QUEUES.get(attempt - 1);
  }
}

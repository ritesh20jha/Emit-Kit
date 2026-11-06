package dev.emitkit.ai;

import dev.emitkit.gen.core.DocsAiAssistant;
import dev.emitkit.gen.core.DocsExamples;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Tag;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Asks a Gemini-backed {@link ChatClient} for code examples, one call per tag.
 *
 * <p>Every failure (timeout, transport error, malformed JSON) yields an empty map, so the docs
 * generator falls back to its template text. A failed call is retried up to {@code maxRetries}
 * times. The timeout applies to each attempt. Errors are logged without the API key or the prompt.
 */
public final class GeminiDocsAiAssistant implements DocsAiAssistant {

  private static final Logger LOG = LoggerFactory.getLogger(GeminiDocsAiAssistant.class);

  // Virtual threads are daemon threads, so the shared pool never blocks JVM shutdown.
  private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

  private final ChatClient client;
  private final Duration timeout;
  private final int maxRetries;

  /** Creates an assistant that retries a failed call once. */
  public GeminiDocsAiAssistant(ChatClient client, Duration timeout) {
    this(client, timeout, 1);
  }

  /**
   * Creates an assistant.
   *
   * @param maxRetries retries after the first failed attempt; 0 means a single attempt
   */
  public GeminiDocsAiAssistant(ChatClient client, Duration timeout, int maxRetries) {
    this.client = Objects.requireNonNull(client, "client");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
    }
    this.maxRetries = maxRetries;
  }

  @Override
  public Map<String, DocsExamples> examples(Tag tag, List<Operation> ops) {
    Objects.requireNonNull(tag, "tag");
    Objects.requireNonNull(ops, "ops");
    if (ops.isEmpty()) {
      return Map.of();
    }
    String prompt = DocsPrompt.render(tag, ops);
    int attempts = maxRetries + 1;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return toExamples(callWithTimeout(prompt), ops);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn("Docs AI call interrupted for tag {}; using template examples", tag.name());
        return Map.of();
      } catch (Exception e) {
        // Log the exception type only: messages can echo request URLs or key material.
        LOG.warn(
            "Docs AI attempt {} of {} failed for tag {} ({})",
            attempt,
            attempts,
            tag.name(),
            e.getClass().getSimpleName());
      }
    }
    LOG.warn("Docs AI gave up for tag {}; using template examples", tag.name());
    return Map.of();
  }

  private List<OperationExamples> callWithTimeout(String prompt)
      throws InterruptedException, ExecutionException, TimeoutException {
    Future<List<OperationExamples>> future = CALLS.submit(() -> callModel(prompt));
    try {
      return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } finally {
      // Interrupts the model call if it is still running; a no-op once it has completed.
      future.cancel(true);
    }
  }

  private List<OperationExamples> callModel(String prompt) {
    return client
        .prompt()
        .user(prompt)
        .call()
        .entity(new ParameterizedTypeReference<List<OperationExamples>>() {});
  }

  /** Keeps only replies for operations that were asked about; the first reply per id wins. */
  private static Map<String, DocsExamples> toExamples(
      List<OperationExamples> items, List<Operation> ops) {
    if (items == null) {
      return Map.of();
    }
    Set<String> requested = new HashSet<>();
    for (Operation op : ops) {
      requested.add(op.operationId());
    }
    Map<String, DocsExamples> out = new LinkedHashMap<>();
    for (OperationExamples item : items) {
      if (item == null
          || item.examples() == null
          || !requested.contains(item.operationId())
          || out.containsKey(item.operationId())) {
        continue;
      }
      out.put(item.operationId(), item.examples());
    }
    return Collections.unmodifiableMap(out);
  }

  /** One element of the model's JSON array reply. */
  record OperationExamples(String operationId, DocsExamples examples) {}
}

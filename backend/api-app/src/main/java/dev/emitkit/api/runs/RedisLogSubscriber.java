package dev.emitkit.api.runs;

import dev.emitkit.domain.StepName;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens to a run's Redis channel {@code run-logs:{runId}}, to which the worker publishes each log
 * line as it is written, and the done message when the run finishes. The message format belongs to
 * the worker ({@code LogLineMessage} and {@code RedisRunLogger}), and api-app cannot depend on
 * worker-app, so the format is read here: a line is {@code {seq, ts, level, step, message}} and the
 * done message is {@code {"type":"done"}}.
 */
@Component
class RedisLogSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(RedisLogSubscriber.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DONE = "done";

  private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();

  /**
   * One thread dispatches every message. The container's default starts a thread per message, and
   * then two lines of a run could be handled out of order, which the stream's seq check would drop.
   */
  private final ExecutorService dispatcher =
      Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "run-log-dispatch");
        thread.setDaemon(true);
        return thread;
      });

  RedisLogSubscriber(RedisConnectionFactory connections) {
    container.setConnectionFactory(connections);
    container.setTaskExecutor(dispatcher);
  }

  @PostConstruct
  void start() throws Exception {
    container.afterPropertiesSet();
    container.start();
  }

  @PreDestroy
  void stop() throws Exception {
    container.stop();
    container.destroy();
    dispatcher.shutdownNow();
  }

  /**
   * Starts listening to the run's channel. Each line goes to {@code lines} and the done message to
   * {@code done}, both on a Redis thread. The returned action removes the listener, and the Redis
   * subscription with it once no listener is left on the channel.
   */
  Runnable subscribe(String runId, Consumer<LogLine> lines, Runnable done) {
    ChannelTopic topic = new ChannelTopic("run-logs:" + runId);
    MessageListener listener =
        (message, pattern) -> dispatch(runId, message.getBody(), lines, done);
    container.addMessageListener(listener, topic);
    return () -> container.removeMessageListener(listener, topic);
  }

  private static void dispatch(String runId, byte[] body, Consumer<LogLine> lines, Runnable done) {
    Message message;
    try {
      message = JSON.readValue(body, Message.class);
    } catch (JacksonException e) {
      LOG.warn("run {}: ignored a log message that does not parse", runId);
      return;
    }
    if (DONE.equals(message.type())) {
      done.run();
    } else if (message.seq() != null) {
      lines.accept(
          new LogLine(
              message.seq(), message.ts(), message.level(), message.step(), message.message()));
    }
  }

  /** The worker's message: {@code type} is set only on the done message, the rest on a line. */
  private record Message(
      @Nullable String type,
      @Nullable Integer seq,
      @Nullable Instant ts,
      @Nullable String level,
      @Nullable StepName step,
      @Nullable String message) {}
}

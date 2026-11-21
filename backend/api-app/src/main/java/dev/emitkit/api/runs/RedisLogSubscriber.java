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
 * Listens to a run's channel {@code run-logs:{runId}}. The worker's messages are a line {@code
 * {seq, ts, level, step, message}} and, once the run finishes, {@code {"type":"done"}}. The format
 * belongs to the worker ({@code LogLineMessage}, {@code RedisRunLogger}); api-app cannot depend on
 * worker-app, so it is read here.
 */
@Component
class RedisLogSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(RedisLogSubscriber.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();

  /**
   * One thread dispatches every message, and it only queues work. The container's default starts a
   * thread per message, so two lines of a run could be handled out of order.
   */
  private final ExecutorService dispatcher =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("run-log-dispatch").factory());

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
   * Listens to the run's channel: each line goes to {@code lines}, the done message to {@code
   * done}. The returned action removes the listener, and the subscription with it.
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
    if ("done".equals(message.type())) {
      done.run();
    } else if (message.seq() != null) {
      lines.accept(
          new LogLine(
              message.seq(), message.ts(), message.level(), message.step(), message.message()));
    }
  }

  /** The worker's message: {@code type} is set only on done, the rest only on a line. */
  private record Message(
      @Nullable String type,
      @Nullable Integer seq,
      @Nullable Instant ts,
      @Nullable String level,
      @Nullable StepName step,
      @Nullable String message) {}
}

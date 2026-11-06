package dev.emitkit.ai;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Scripted {@link ChatModel} for tests: replies with fixed text, sleeps, or always fails. */
final class FakeChatModel implements ChatModel {

  private final String reply; // null means fail
  private final Duration delay;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile String lastPrompt = "";

  private FakeChatModel(String reply, Duration delay) {
    this.reply = reply;
    this.delay = delay;
  }

  static FakeChatModel replying(String text) {
    return new FakeChatModel(text, Duration.ZERO);
  }

  static FakeChatModel delaying(Duration delay) {
    return new FakeChatModel("[]", delay);
  }

  static FakeChatModel failing() {
    return new FakeChatModel(null, Duration.ZERO);
  }

  int calls() {
    return calls.get();
  }

  String lastPrompt() {
    return lastPrompt;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    calls.incrementAndGet();
    lastPrompt = prompt.getContents();
    pause();
    if (reply == null) {
      throw new IllegalStateException("simulated model failure");
    }
    return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.error(new UnsupportedOperationException("streaming is not used in tests"));
  }

  private void pause() {
    if (delay.isZero()) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while delaying", e);
    }
  }
}

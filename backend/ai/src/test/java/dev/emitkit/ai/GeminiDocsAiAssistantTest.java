package dev.emitkit.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class GeminiDocsAiAssistantTest {

  private static final Tag PETS = new Tag("pets", "Pet operations");

  private static final Operation LIST_PETS =
      operation(
          "listPets",
          "GET",
          "/pets",
          new Parameter(
              "limit",
              ParamLocation.QUERY,
              false,
              new PrimitiveSchema(PrimitiveType.INTEGER, null, false, null),
              null));

  private static final Operation GET_PET =
      operation(
          "getPet",
          "GET",
          "/pets/{petId}",
          new Parameter(
              "petId",
              ParamLocation.PATH,
              true,
              new PrimitiveSchema(PrimitiveType.INTEGER, null, false, null),
              null));

  @Test
  void mapsRecordedResponseToExamples() {
    var fake = FakeChatModel.replying(resource("recorded/pets-tag.json"));
    var ai = new GeminiDocsAiAssistant(ChatClient.create(fake), Duration.ofSeconds(30));

    var out = ai.examples(PETS, List.of(LIST_PETS, GET_PET));

    assertThat(out).containsOnlyKeys("listPets", "getPet");
    assertThat(out.get("listPets").curl()).startsWith("curl ");
    assertThat(fake.calls()).isEqualTo(1);
    assertThat(fake.lastPrompt()).contains("GET /pets").contains("petId");
  }

  @Test
  void malformedJson_returnsEmptyMap() {
    var fake = FakeChatModel.replying("not json");
    var ai = new GeminiDocsAiAssistant(ChatClient.create(fake), Duration.ofSeconds(30));

    var out = ai.examples(PETS, List.of(LIST_PETS, GET_PET));

    assertThat(out).isEmpty();
  }

  @Test
  void timeout_returnsEmptyMap() {
    var fake = FakeChatModel.delaying(Duration.ofSeconds(2));
    var ai = new GeminiDocsAiAssistant(ChatClient.create(fake), Duration.ofMillis(100));

    long started = System.nanoTime();
    var out = ai.examples(PETS, List.of(LIST_PETS, GET_PET));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(out).isEmpty();
    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void retriesOnceThenGivesUp() {
    var fake = FakeChatModel.failing();
    var ai = new GeminiDocsAiAssistant(ChatClient.create(fake), Duration.ofSeconds(30));

    var out = ai.examples(PETS, List.of(LIST_PETS, GET_PET));

    assertThat(out).isEmpty();
    assertThat(fake.calls()).isEqualTo(2);
  }

  @Test
  void unknownOperationIdsInReply_areDropped() {
    var reply =
        """
        [
          {"operationId": "listPets", "examples": {"curl": "curl -s https://api.example.com/v1/pets", "typescript": "t", "python": "p", "java": "j", "notes": ""}},
          {"operationId": "ghostOperation", "examples": {"curl": "curl -s https://api.example.com/ghost", "typescript": "t", "python": "p", "java": "j", "notes": ""}}
        ]
        """;
    var fake = FakeChatModel.replying(reply);
    var ai = new GeminiDocsAiAssistant(ChatClient.create(fake), Duration.ofSeconds(30));

    var out = ai.examples(PETS, List.of(LIST_PETS, GET_PET));

    assertThat(out).containsOnlyKeys("listPets");
  }

  private static Operation operation(String id, String method, String path, Parameter... params) {
    return new Operation(
        id,
        false,
        method,
        path,
        "pets",
        null,
        null,
        List.of(params),
        null,
        Map.of(),
        List.of(),
        false);
  }

  private static String resource(String name) {
    try (var in = GeminiDocsAiAssistantTest.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

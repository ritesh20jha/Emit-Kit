# AI-01 · Spring AI docs assistant

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a per-run Gemini ChatClient from the project's own key and use it to produce structured doc examples per tag, falling back silently on any failure.

**Lane:** CORE · **Depends on:** [GEN-01](GEN-01-generator-spi.md), [CORE-02](CORE-02-secret-cipher.md) · **Size budget:** ~550 changed lines (hard limit 1000) · **Branch:** `ticket/ai-01`

**Spec:** v1 §6.1

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/ai/src/main/java/dev/emitkit/ai/ChatModelFactory.java`, `AiProperties.java`, `GeminiDocsAiAssistant.java`, `DocsPrompt.java`
- Create: `backend/ai/src/main/resources/prompts/docs-examples.st` (prompt template)
- Modify: `backend/ai/build.gradle.kts` (Spring AI BOM, `spring-ai-google-genai` or the current Gemini chat module)
- Test: `backend/ai/src/test/java/dev/emitkit/ai/GeminiDocsAiAssistantTest.java`, `ChatModelFactoryTest.java`, `FakeChatModel.java`, `src/test/resources/recorded/*.json`

## Interfaces

- **Consumes:** `DocsAiAssistant`, `DocsExamples`, `Tag`, `Operation` (GEN-01, SPEC-01).
- **Produces:**
```java
public class ChatModelFactory {
  public ChatClient create(String provider, @Nullable String model, String apiKey); // provider "gemini" only; else IllegalArgumentException
}
public class GeminiDocsAiAssistant implements DocsAiAssistant {   // DocsAiAssistant, DocsExamples from GEN-01
  public GeminiDocsAiAssistant(ChatClient client, Duration timeout);
  public Map<String, DocsExamples> examples(Tag tag, List<Operation> ops);   // key = operationId
}
```
- Properties: `emitkit.ai.gemini.default-model` (required), `emitkit.ai.timeout` (default 30s), `emitkit.ai.max-retries` (default 1). Set `spring.ai.model.chat=none` in both apps' `application.yml`.
- Structured output: `.call().entity(new ParameterizedTypeReference<List<OperationExamples>>(){})` with `record OperationExamples(String operationId, DocsExamples examples)`.

## Tasks

### Task 1: Assistant with structured output and fallbacks

- [ ] **Step 1: Write the failing test**

```java
class GeminiDocsAiAssistantTest {
  @Test void mapsRecordedResponseToExamples() {
    var fake = FakeChatModel.replying(resource("recorded/pets-tag.json"));
    var ai = new GeminiDocsAiAssistant(ChatClient.create(fake), Duration.ofSeconds(30));
    var out = ai.examples(petsTag, List.of(listPets, getPet));
    assertThat(out).containsOnlyKeys("listPets", "getPet");
    assertThat(out.get("listPets").curl()).startsWith("curl ");
    assertThat(fake.calls()).isEqualTo(1);                    // one call per tag
  }
  @Test void malformedJson_returnsEmptyMap() { /* reply "not json" → isEmpty(), no exception */ }
  @Test void timeout_returnsEmptyMap() { /* FakeChatModel.delaying(2s), timeout 100ms → empty */ }
  @Test void retriesOnceThenGivesUp() { /* fails twice → empty, fake.calls()==2 */ }
  @Test void unknownOperationIdsInReply_areDropped() { }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :ai:test --tests '*GeminiDocsAiAssistantTest'`

- [ ] **Step 3: Implement** — Run the call on a virtual thread with `future.get(timeout)`; catch everything, log a WARN without the key or prompt body, return `Map.of()`. Prompt from `DocsPrompt.render(tag, ops)` lists method, path, params and schema names only.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :ai:test --tests '*GeminiDocsAiAssistantTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(ai): docs assistant with structured output"`

### Task 2: Chat model factory

- [ ] **Step 1: Write the failing test**

```java
@Test void emptyModel_usesConfiguredDefault() {
  var f = new ChatModelFactory(new AiProperties("gemini-default-x", Duration.ofSeconds(30), 1));
  var options = f.optionsFor("gemini", null);
  assertThat(options.getModel()).isEqualTo("gemini-default-x");
}
@Test void explicitModelWins() { }
@Test void unknownProvider_rejected() { assertThatThrownBy(() -> f.create("openai", null, "k")).isInstanceOf(IllegalArgumentException.class); }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :ai:test --tests '*ChatModelFactoryTest'`

- [ ] **Step 3: Implement** — `create` builds the Gemini chat model with the API key passed directly (no env/global bean) and wraps it in `ChatClient.create(model)`. Expose package-private `optionsFor` for the test.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :ai:test --tests '*ChatModelFactoryTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(ai): per-project chat model factory"`

---

## Done when

- [ ] All tasks above are committed on `ticket/ai-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

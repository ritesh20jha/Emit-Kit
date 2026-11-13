package dev.emitkit.gen.docs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.gen.core.DocsAiAssistant;
import dev.emitkit.gen.core.DocsExamples;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.gen.core.fixtures.FixtureSpecs;
import dev.emitkit.gen.core.golden.GoldenFiles;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Schema;
import dev.emitkit.spec.model.Tag;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocsGeneratorTest {

  private final DocsGenerator generator = new DocsGenerator();

  @ParameterizedTest
  @ValueSource(strings = {"petstore", "auth-schemes", "multi-tag"})
  void matchesGolden(String fixture) {
    GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
  }

  @Test
  void outputIsByteStable() {
    var m = FixtureSpecs.load("petstore");
    assertThat(generator.generate(m, ctx())).isEqualTo(generator.generate(m, ctx()));
  }

  @Test
  void idIsDOCS() {
    assertThat(generator.id()).isEqualTo(GeneratorId.DOCS);
  }

  @Test
  void aiExamplesUsedWhenPresent_templateOtherwise() {
    var ai =
        ai(Map.of("listPets", new DocsExamples("curl AI", "ts AI", "py AI", "java AI", "AI note")));
    var md = content(generator.generate(FixtureSpecs.load("petstore"), ctx(ai)), "pets.md");
    assertThat(section(md, "listPets")).contains("curl AI").contains("### Notes\n\nAI note");
    assertThat(section(md, "getPetById")).doesNotContain("AI").contains("curl -X GET");
  }

  @Test
  void aiCalledOncePerTag() {
    var calls = new AtomicInteger();
    generator.generate(
        FixtureSpecs.load("multi-tag"),
        ctx((tag, ops) -> {
          calls.incrementAndGet();
          return Map.of();
        }));
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void aiThrowing_fallsBackToTemplate() {
    DocsAiAssistant failing =
        (tag, ops) -> {
          throw new IllegalStateException("model unavailable");
        };
    var files = generator.generate(FixtureSpecs.load("petstore"), ctx(failing));
    assertThat(section(content(files, "pets.md"), "getPetById")).contains("curl -X GET");
  }

  @Test
  void rendersDeprecatedProseAndSchemaTables() {
    var pet =
        new ObjectSchema(
            Map.of(
                "name",
                new PrimitiveSchema(PrimitiveType.STRING, null, false, "Pet name |\nnickname")),
            Set.of("name"),
            null,
            false,
            null);
    Schema petRef = new RefSchema("Pet", false, null);
    var op =
        new Operation(
            "getPet",
            false,
            "GET",
            "/pets/{id}",
            "pets",
            "Get a pet",
            "Returns one pet.",
            List.of(),
            new RequestBody(true, "application/json", petRef),
            Map.of("200", new Response("200", "ok", petRef)),
            List.of(),
            true);
    var model = model(List.of(), List.of(op), Map.of("Pet", pet));
    assertThat(content(generator.generate(model, ctx()), "pets.md"))
        .contains("**Deprecated.** `GET /pets/{id}`")
        .contains("Get a pet\n\nReturns one pet.")
        .contains(
            "| Field | Type | Required | Description |\n"
                + "| --- | --- | --- | --- |\n"
                + "| name | string | Yes | Pet name \\| nickname |")
        .contains("| 200 | ok | Pet |")
        .contains("**200 response fields**");
  }

  @Test
  void tagFileStemsAvoidIndexAndCollisions() {
    var model =
        model(
            List.of(new Tag("index", null), new Tag("Pet Store", null), new Tag("pet-store", null)),
            List.of(
                operation("a", "index", "/a", List.of()),
                operation("b", "Pet Store", "/b", List.of()),
                operation("c", "pet-store", "/c", List.of())),
            Map.of());
    assertThat(generator.generate(model, ctx()))
        .extracting(GeneratedFile::path)
        .containsExactly(
            ".emitkit/docs/v1.2.3/index.md",
            ".emitkit/docs/v1.2.3/index-2.md",
            ".emitkit/docs/v1.2.3/pet-store.md",
            ".emitkit/docs/v1.2.3/pet-store-2.md");
  }

  /** An assistant that returns the given examples for every tag. */
  private static DocsAiAssistant ai(Map<String, DocsExamples> examples) {
    return (tag, ops) -> examples;
  }

  private static GeneratorContext ctx() {
    return ctx(null);
  }

  private static GeneratorContext ctx(DocsAiAssistant ai) {
    return new GeneratorContext(
        ".emitkit/",
        "1.2.3",
        new PackageNames("acme", "petstore", null, null, null),
        List.of(),
        Optional.ofNullable(ai));
  }

  private static String content(List<GeneratedFile> files, String name) {
    return files.stream()
        .filter(f -> f.path().endsWith("/" + name))
        .findFirst()
        .orElseThrow()
        .content();
  }

  /** The text of one operation's heading and everything up to the next operation heading. */
  private static String section(String md, String operationId) {
    int start = md.indexOf("\n## " + operationId + "\n");
    assertThat(start).as("section for %s", operationId).isGreaterThanOrEqualTo(0);
    int end = md.indexOf("\n## ", start + 1);
    return md.substring(start, end < 0 ? md.length() : end);
  }

  private static ApiModel model(List<Tag> tags, List<Operation> ops, Map<String, Schema> schemas) {
    return new ApiModel(
        new Info("Test", "1.0.0", null, null), List.of(), Map.of(), tags, ops, schemas);
  }

  private static Operation operation(
      String operationId, String tag, String path, List<Parameter> parameters) {
    return new Operation(
        operationId,
        false,
        "GET",
        path,
        tag,
        null,
        null,
        parameters,
        null,
        Map.of(),
        List.of(),
        false);
  }
}

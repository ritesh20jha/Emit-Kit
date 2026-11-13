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
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.Tag;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        new StubAi(
            Map.of(
                "listPets", new DocsExamples("curl AI", "ts AI", "py AI", "java AI", "AI note")));
    var md = content(generator.generate(FixtureSpecs.load("petstore"), ctx(ai)), "pets.md");
    assertThat(md).contains("curl AI").contains("AI note");
    assertThat(section(md, "getPetById")).doesNotContain("AI").contains("curl -X GET");
  }

  @Test
  void aiCalledOncePerTag() {
    var ai = new StubAi(Map.of());
    generator.generate(FixtureSpecs.load("multi-tag"), ctx(ai));
    assertThat(ai.calls()).isEqualTo(3);
  }

  @Test
  void aiThrowing_fallsBackToTemplate() {
    var files = generator.generate(FixtureSpecs.load("petstore"), ctx(new StubAi(Map.of(), true)));
    assertThat(section(content(files, "pets.md"), "getPetById")).contains("curl -X GET");
  }

  @Test
  void pipesAndNewlinesInTableCellsAreEscaped() {
    var param =
        new Parameter(
            "q",
            ParamLocation.QUERY,
            false,
            new PrimitiveSchema(PrimitiveType.STRING, null, false, null),
            "one | two\nthree");
    var model =
        model(
            List.of(new Tag("pets", null)),
            List.of(operation("searchPets", "pets", "/pets/search", List.of(param))));
    assertThat(content(generator.generate(model, ctx()), "pets.md"))
        .contains("| q | query | No | string | one \\| two three |");
  }

  @Test
  void tagFileStemsAvoidIndexAndCollisions() {
    var model =
        model(
            List.of(new Tag("index", null), new Tag("Pet Store", null), new Tag("pet-store", null)),
            List.of(
                operation("a", "index", "/a", List.of()),
                operation("b", "Pet Store", "/b", List.of()),
                operation("c", "pet-store", "/c", List.of())));
    assertThat(generator.generate(model, ctx()))
        .extracting(GeneratedFile::path)
        .containsExactly(
            ".emitkit/docs/v1.2.3/index.md",
            ".emitkit/docs/v1.2.3/index-2.md",
            ".emitkit/docs/v1.2.3/pet-store.md",
            ".emitkit/docs/v1.2.3/pet-store-2.md");
  }

  /** Records calls and returns canned examples, or throws when constructed to fail. */
  private static final class StubAi implements DocsAiAssistant {
    private final Map<String, DocsExamples> examples;
    private final boolean fail;
    private int calls;

    StubAi(Map<String, DocsExamples> examples) {
      this(examples, false);
    }

    StubAi(Map<String, DocsExamples> examples, boolean fail) {
      this.examples = examples;
      this.fail = fail;
    }

    @Override
    public Map<String, DocsExamples> examples(Tag tag, List<Operation> ops) {
      calls++;
      if (fail) {
        throw new IllegalStateException("model unavailable");
      }
      return examples;
    }

    int calls() {
      return calls;
    }
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

  private static ApiModel model(List<Tag> tags, List<Operation> operations) {
    return new ApiModel(
        new Info("Test", "1.0.0", null, null), List.of(), Map.of(), tags, operations, Map.of());
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

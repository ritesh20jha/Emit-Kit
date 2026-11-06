package dev.emitkit.gen.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.gen.core.golden.GoldenFiles;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Info;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

@SuppressWarnings("unchecked")
class WorkflowsGeneratorTest {

  private static final String NPM = ".github/workflows/emitkit-publish-npm.yml";
  private static final String PYPI = ".github/workflows/emitkit-publish-pypi.yml";
  private static final String MAVEN = ".github/workflows/emitkit-publish-maven.yml";

  @ParameterizedTest
  @ValueSource(strings = {"typescript", "python", "java", "typescript,python,java"})
  void matchesGolden(String langs) {
    List<GeneratedFile> files = gen("Pets", ".emitkit/", List.of(langs.split(",")));
    assertThat(files).isNotEmpty();
    GoldenFiles.assertMatches("langs-" + langs.replace(',', '-'), files);
  }

  @Test
  void onlySelectedLanguages() {
    assertThat(gen("Pets", ".emitkit/", List.of("python")))
        .extracting(GeneratedFile::path)
        .containsExactly(PYPI);
  }

  @Test
  void yamlParsesAndTriggersOnSdkTags() {
    List<GeneratedFile> files = gen("Pets", ".emitkit/", List.of("typescript", "python", "java"));
    assertThat(files).hasSize(3);
    for (GeneratedFile f : files) {
      Map<String, Object> on = onBlock(parse(f));
      assertThat(((Map<String, Object>) on.get("push")).get("tags")).isEqualTo(List.of("sdk/v*"));
    }
  }

  @Test
  void emitsOneFilePerLanguageInFixedOrderWithoutDuplicates() {
    assertThat(gen("Pets", ".emitkit/", List.of("java", "typescript", "typescript", "cobol")))
        .extracting(GeneratedFile::path)
        .containsExactly(NPM, MAVEN);
  }

  @Test
  void secretsAreLiteralGithubExpressions() {
    assertThat(content(gen("Pets", ".emitkit/", List.of("typescript"))))
        .contains("NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}");
    assertThat(content(gen("Pets", ".emitkit/", List.of("python"))))
        .contains("TWINE_PASSWORD: ${{ secrets.PYPI_TOKEN }}");
    assertThat(content(gen("Pets", ".emitkit/", List.of("java"))))
        .contains("ORG_GRADLE_PROJECT_mavenUsername: ${{ secrets.MAVEN_USERNAME }}")
        .contains("ORG_GRADLE_PROJECT_mavenPassword: ${{ secrets.MAVEN_PASSWORD }}")
        .contains("ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.MAVEN_GPG_KEY }}");
  }

  @Test
  void titleWithYamlSpecialCharsKeepsWorkflowValid() {
    String title = "Pets: \"v2\" # draft \\ beta";
    List<GeneratedFile> files = gen(title, ".emitkit/", List.of("typescript", "python", "java"));
    assertThat(files).hasSize(3);
    for (GeneratedFile f : files) {
      assertThat(parse(f).get("name")).isEqualTo("Publish " + title + " " + langName(f) + " SDK");
    }
  }

  @Test
  void workingDirectoryFollowsOutputDirButPathDoesNot() {
    for (String outputDir : List.of("out/", "out")) {
      List<GeneratedFile> files = gen("Pets", outputDir, List.of("typescript", "python", "java"));
      assertThat(files).hasSize(3);
      for (GeneratedFile f : files) {
        assertThat(f.path()).startsWith(".github/workflows/");
        String lang =
            f.path().contains("npm") ? "typescript" : f.path().contains("pypi") ? "python" : "java";
        assertThat(steps(parse(f)))
            .filteredOn(step -> step.containsKey("working-directory"))
            .isNotEmpty()
            .allSatisfy(
                step -> assertThat(step.get("working-directory")).isEqualTo("out/sdk/" + lang));
      }
    }
  }

  @Test
  void mavenWorkflowPinsGradleVersionOnSetupGradleStep() {
    GeneratedFile maven = gen("Pets", ".emitkit/", List.of("java")).get(0);
    List<Map<String, Object>> setupGradle =
        steps(parse(maven)).stream()
            .filter(
                step -> String.valueOf(step.get("uses")).startsWith("gradle/actions/setup-gradle@"))
            .toList();
    assertThat(setupGradle).hasSize(1);
    assertThat((Map<String, Object>) setupGradle.get(0).get("with"))
        .containsEntry("gradle-version", "8.14.3");
  }

  @Test
  void actionsArePinnedByMajorVersion() {
    List<GeneratedFile> files = gen("Pets", ".emitkit/", List.of("typescript", "python", "java"));
    assertThat(files).hasSize(3);
    for (GeneratedFile f : files) {
      for (Map<String, Object> step : steps(parse(f))) {
        if (step.containsKey("uses")) {
          assertThat((String) step.get("uses")).matches("[\\w.-]+(/[\\w.-]+)+@v\\d+");
        }
      }
    }
  }

  private static String langName(GeneratedFile f) {
    return f.path().contains("npm") ? "TypeScript" : f.path().contains("pypi") ? "Python" : "Java";
  }

  private static String content(List<GeneratedFile> files) {
    assertThat(files).hasSize(1);
    return files.get(0).content();
  }

  private static Map<String, Object> parse(GeneratedFile f) {
    return new Yaml().load(f.content());
  }

  private static Map<String, Object> onBlock(Map<String, Object> y) {
    // SnakeYAML is YAML 1.1: an unquoted `on:` key loads as Boolean.TRUE.
    Object on = y.containsKey(Boolean.TRUE) ? y.get(Boolean.TRUE) : y.get("on");
    return (Map<String, Object>) on;
  }

  private static List<Map<String, Object>> steps(Map<String, Object> y) {
    Map<String, Object> jobs = (Map<String, Object>) y.get("jobs");
    Map<String, Object> job = (Map<String, Object>) jobs.get("publish");
    return (List<Map<String, Object>>) job.get("steps");
  }

  static List<GeneratedFile> gen(String title, String outputDir, List<String> langs) {
    return new WorkflowsGenerator()
        .generate(
            new ApiModel(
                new Info(title, "1.0.0", null, null),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Map.of()),
            new GeneratorContext(
                outputDir,
                "1.0.0",
                new PackageNames("@acme", "pets", "pets", "dev.acme", "pets"),
                langs,
                Optional.empty()));
  }
}

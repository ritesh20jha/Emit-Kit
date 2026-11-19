package dev.emitkit.gen.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.emitkit.gen.cli.CliGenerator;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.gen.core.fixtures.FixtureSpecs;
import dev.emitkit.gen.javasdk.JavaSdkGenerator;
import dev.emitkit.gen.mcp.McpGenerator;
import dev.emitkit.gen.pythonsdk.PythonSdkGenerator;
import dev.emitkit.gen.typescriptsdk.TypeScriptSdkGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every generator's output for every shared fixture must pass the toolchain image's checks (the
 * SBX-02 check.sh contract). Skips when the image is missing unless {@code compile-tests.required}
 * is true, in which case it fails.
 */
class CompileAllFixturesIT {

  private static final List<GeneratorId> GENERATORS =
      List.of(
          GeneratorId.TS_SDK,
          GeneratorId.PY_SDK,
          GeneratorId.JAVA_SDK,
          GeneratorId.CLI,
          GeneratorId.MCP);

  private static final Map<GeneratorId, Generator> GENERATORS_BY_ID =
      Map.of(
          GeneratorId.TS_SDK, new TypeScriptSdkGenerator(),
          GeneratorId.PY_SDK, new PythonSdkGenerator(),
          GeneratorId.JAVA_SDK, new JavaSdkGenerator(),
          GeneratorId.CLI, new CliGenerator(),
          GeneratorId.MCP, new McpGenerator());

  private final ToolchainContainer toolchain = new ToolchainContainer();

  @BeforeAll
  static void requireToolchain() {
    String reason = new ToolchainContainer().unavailableReason();
    if (reason == null) {
      return;
    }
    if (Boolean.getBoolean("compile-tests.required")) {
      fail("compile-tests.required=true but " + reason);
    }
    assumeTrue(false, reason);
  }

  static Stream<Arguments> fixturesTimesGenerators() {
    return FixtureSpecs.ALL.stream()
        .flatMap(fixture -> GENERATORS.stream().map(gen -> Arguments.of(fixture, gen)));
  }

  @ParameterizedTest(name = "{0} / {1}")
  @MethodSource("fixturesTimesGenerators")
  void generatedCodePassesChecks(String fixture, GeneratorId gen) {
    Target target = Target.of(gen);
    List<GeneratedFile> files =
        GENERATORS_BY_ID.get(gen).generate(FixtureSpecs.load(fixture), ctx());
    assertThat(files)
        .as("%s emits a %s project", gen, target.language())
        .anyMatch(file -> file.path().endsWith(target.marker()));

    ToolchainContainer.Report report = toolchain.check(files, Set.of(target.language()));
    assertThat(report.results()).isNotEmpty();
    assertThat(report.results())
        .allSatisfy(result ->
            assertThat(result.exitCode())
                .as(result.tool() + " in " + result.dir() + "\n" + result.output())
                .isZero());
  }

  /** The check.sh language for a generator and the file that marks its project. */
  private record Target(String language, String marker) {

    static Target of(GeneratorId gen) {
      return switch (gen) {
        case TS_SDK, CLI, MCP -> new Target("typescript", "tsconfig.json");
        case PY_SDK -> new Target("python", "pyproject.toml");
        case JAVA_SDK -> new Target("java", "build.gradle.kts");
        default -> throw new IllegalArgumentException("no toolchain check for " + gen);
      };
    }
  }

  /** Every ecosystem is named, so each generator emits its full file set. */
  private static GeneratorContext ctx() {
    var names = new PackageNames("acme", "petstore-sdk", "petstore", "com.acme", "petstore-sdk");
    return new GeneratorContext(
        ".emitkit/", "1.2.3", names, List.of("typescript", "python", "java"), Optional.empty());
  }
}

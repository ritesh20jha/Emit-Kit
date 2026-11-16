package dev.emitkit.api.projects;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OutputKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks a config request against the rules the generators depend on. Every violation is collected
 * before the check fails, so one response names each field that is wrong, once.
 */
final class ConfigValidator {

  /** The only AI provider the worker supports (spec section 6.1); a blank one is stored as this. */
  static final String DEFAULT_PROVIDER = "gemini";

  private static final Set<String> LANGUAGES = Set.of("typescript", "python", "java");
  private static final Pattern NPM_SCOPE = Pattern.compile("^[a-z0-9-~][a-z0-9-._~]*$");

  /** The width of the project_configs text columns in V2__projects.sql. */
  private static final int TEXT_MAX = 255;

  private ConfigValidator() {}

  /**
   * Fails when the request breaks a rule. The error shape matches bean validation: a {@code errors}
   * list of {@code field} and {@code message} entries.
   *
   * @throws AppException VALIDATION_FAILED listing every invalid field
   */
  static void check(ConfigRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    checkSdk(request, errors);
    checkOutputDir(request.outputDir(), errors);
    checkProvider(request.aiProvider(), errors);
    checkLength(errors, "npmScope", request.npmScope());
    checkLength(errors, "pypiName", request.pypiName());
    checkLength(errors, "mavenGroupId", request.mavenGroupId());
    checkLength(errors, "mavenArtifactId", request.mavenArtifactId());
    checkLength(errors, "aiModel", request.aiModel());
    if (!errors.isEmpty()) {
      throw new AppException(
          ErrorCode.VALIDATION_FAILED,
          "Request validation failed.",
          Map.of("errors", errors),
          null);
    }
  }

  private static void checkSdk(ConfigRequest request, List<Map<String, String>> errors) {
    if (request.outputs().stream().anyMatch(Objects::isNull)) {
      error(errors, "outputs", "outputs must not contain null.");
    }
    List<String> languages = request.sdkLanguages() == null ? List.of() : request.sdkLanguages();
    if (request.outputs().contains(OutputKind.SDK) && languages.isEmpty()) {
      error(errors, "sdkLanguages", "An SDK output needs at least one language.");
    }
    for (String language : languages) {
      if (language == null) {
        error(errors, "sdkLanguages", "sdkLanguages must not contain null.");
      } else if (!LANGUAGES.contains(language)) {
        error(errors, "sdkLanguages", "Unknown SDK language: " + language + ".");
      }
    }
    if (languages.contains("typescript")) {
      checkNpmScope(request.npmScope(), errors);
    }
    if (languages.contains("python") && isBlank(request.pypiName())) {
      error(errors, "pypiName", "pypiName is required for python.");
    }
    if (languages.contains("java")) {
      if (isBlank(request.mavenGroupId())) {
        error(errors, "mavenGroupId", "mavenGroupId is required for java.");
      }
      if (isBlank(request.mavenArtifactId())) {
        error(errors, "mavenArtifactId", "mavenArtifactId is required for java.");
      }
    }
  }

  /** The npm scope may be empty or absent; when set it must match the npm name rule. */
  private static void checkNpmScope(@Nullable String scope, List<Map<String, String>> errors) {
    if (scope == null || scope.isEmpty()) {
      return;
    }
    if (!NPM_SCOPE.matcher(scope).matches()) {
      error(errors, "npmScope", "npmScope must be lowercase letters, digits, '-' or '.'.");
    }
  }

  private static void checkOutputDir(String outputDir, List<Map<String, String>> errors) {
    String problem = outputDirProblem(outputDir);
    if (problem != null) {
      error(errors, "outputDir", problem);
    }
  }

  /**
   * The first rule the output directory breaks, or null when it is usable. The generators build
   * paths as {@code outputDir + "sdk/..."} and the VCS matches them by prefix, so the directory
   * must be a clean relative path: slash-separated, with no empty, {@code .} or {@code ..} segment.
   */
  private static @Nullable String outputDirProblem(String outputDir) {
    if (outputDir.contains("\\")) {
      return "outputDir must use '/' separators, not backslashes.";
    }
    if (outputDir.startsWith("/")) {
      return "outputDir must be relative, without a leading slash.";
    }
    if (!outputDir.endsWith("/")) {
      return "outputDir must end with a slash.";
    }
    if (outputDir.codePointCount(0, outputDir.length()) > TEXT_MAX) {
      return "outputDir must be at most " + TEXT_MAX + " characters.";
    }
    // The trailing slash is the only one allowed; every segment before it must be a real name.
    for (String segment : outputDir.substring(0, outputDir.length() - 1).split("/", -1)) {
      if (segment.isEmpty()) {
        return "outputDir must not contain empty segments.";
      }
      if (segment.equals(".")) {
        return "outputDir must not contain '.' segments.";
      }
      if (segment.equals("..")) {
        return "outputDir must not contain '..' segments.";
      }
    }
    return null;
  }

  /** A blank provider means the default; any other value must be the one supported provider. */
  private static void checkProvider(@Nullable String provider, List<Map<String, String>> errors) {
    if (!isBlank(provider) && !DEFAULT_PROVIDER.equals(provider)) {
      error(errors, "aiProvider", "aiProvider must be \"gemini\", the only supported provider.");
    }
  }

  /** A value longer than its column is refused here, not by the database. */
  private static void checkLength(
      List<Map<String, String>> errors, String field, @Nullable String value) {
    if (value != null && value.codePointCount(0, value.length()) > TEXT_MAX) {
      error(errors, field, field + " must be at most " + TEXT_MAX + " characters.");
    }
  }

  private static void error(List<Map<String, String>> errors, String field, String message) {
    errors.add(Map.of("field", field, "message", message));
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }
}

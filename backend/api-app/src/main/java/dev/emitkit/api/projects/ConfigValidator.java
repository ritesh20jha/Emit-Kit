package dev.emitkit.api.projects;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OutputKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks a config request against the rules the generators depend on. Every violation is collected
 * before the check fails, so one response names each field that is wrong.
 */
final class ConfigValidator {

  private static final Set<String> LANGUAGES = Set.of("typescript", "python", "java");
  private static final Pattern NPM_SCOPE = Pattern.compile("^[a-z0-9-~][a-z0-9-._~]*$");

  /** The widths of the project_configs columns in V2__projects.sql. */
  private static final int TEXT_MAX = 255;

  private static final int PROVIDER_MAX = 32;

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
    checkLength(errors, "outputDir", request.outputDir(), TEXT_MAX);
    checkLength(errors, "npmScope", request.npmScope(), TEXT_MAX);
    checkLength(errors, "pypiName", request.pypiName(), TEXT_MAX);
    checkLength(errors, "mavenGroupId", request.mavenGroupId(), TEXT_MAX);
    checkLength(errors, "mavenArtifactId", request.mavenArtifactId(), TEXT_MAX);
    checkLength(errors, "aiProvider", request.aiProvider(), PROVIDER_MAX);
    checkLength(errors, "aiModel", request.aiModel(), TEXT_MAX);
    if (!errors.isEmpty()) {
      throw new AppException(
          ErrorCode.VALIDATION_FAILED,
          "Request validation failed.",
          Map.of("errors", errors),
          null);
    }
  }

  private static void checkSdk(ConfigRequest request, List<Map<String, String>> errors) {
    List<String> languages = request.sdkLanguages() == null ? List.of() : request.sdkLanguages();
    if (request.outputs().contains(OutputKind.SDK) && languages.isEmpty()) {
      error(errors, "sdkLanguages", "An SDK output needs at least one language.");
    }
    for (String language : languages) {
      if (language == null || !LANGUAGES.contains(language)) {
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

  /** The output directory is relative, has no {@code ..} segment and ends with a slash. */
  private static void checkOutputDir(String outputDir, List<Map<String, String>> errors) {
    if (outputDir.startsWith("/")) {
      error(errors, "outputDir", "outputDir must be relative, without a leading slash.");
    }
    if (Arrays.asList(outputDir.split("/")).contains("..")) {
      error(errors, "outputDir", "outputDir must not contain '..' segments.");
    }
    if (!outputDir.endsWith("/")) {
      error(errors, "outputDir", "outputDir must end with a slash.");
    }
  }

  /** A value longer than its column is refused here, not by the database. */
  private static void checkLength(
      List<Map<String, String>> errors, String field, @Nullable String value, int max) {
    if (value != null && value.codePointCount(0, value.length()) > max) {
      error(errors, field, field + " must be at most " + max + " characters.");
    }
  }

  private static void error(List<Map<String, String>> errors, String field, String message) {
    errors.add(Map.of("field", field, "message", message));
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }
}

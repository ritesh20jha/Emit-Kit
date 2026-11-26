package dev.emitkit.api.projects;

import dev.emitkit.domain.OutputMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The fields needed to create a project. {@code repo} is {@code owner/name}. {@code outputRepo} is
 * only read when {@code outputMode} is {@code SEPARATE}.
 */
public record CreateProjectRequest(
    @NotNull Mode mode,
    @NotBlank @Pattern(regexp = "[\\w.-]+/[\\w.-]+") String repo,
    @NotBlank String specPath,
    @NotBlank String defaultBranch,
    @Schema(types = {"string", "null"}) @Nullable Visibility visibility,
    @NotNull OutputMode outputMode,
    @Schema(types = {"string", "null"}) @Nullable String outputRepo) {

  /** EXISTING links a repository that is already there; NEW creates one. */
  public enum Mode {
    EXISTING,
    NEW
  }

  /** The visibility of a repository that is created. */
  public enum Visibility {
    PUBLIC,
    PRIVATE
  }
}

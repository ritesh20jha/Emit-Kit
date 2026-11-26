package dev.emitkit.api.projects;

import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.VersionStrategy;
import java.util.ArrayList;
import java.util.List;

/**
 * The config version 1 that a new project starts with: an SDK output for TypeScript. Every other
 * setting keeps its {@link ProjectConfig} default, which mirrors the V2__projects column defaults.
 */
final class DefaultConfig {

  // The plan says npmName = repo name, but no npmName column exists; npm_scope stays null.

  private DefaultConfig() {}

  /** Builds version 1 of the config of {@code projectId}, saved by {@code userId}. */
  static ProjectConfig version1(String projectId, String userId) {
    var config = new ProjectConfig(null, projectId, 1, VersionStrategy.EMITKIT_MANAGED, userId);
    config.setOutputs(new ArrayList<>(List.of(OutputKind.SDK)));
    config.setSdkLanguages(new ArrayList<>(List.of("typescript")));
    return config;
  }
}

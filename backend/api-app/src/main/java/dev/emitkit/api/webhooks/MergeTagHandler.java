package dev.emitkit.api.webhooks;

import static dev.emitkit.api.webhooks.GitHubWebhookController.forLog;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Tags the version of a generation run when its pull request is merged: {@code sdk/v} and, when MCP
 * is an output, {@code mcp/v}, both at the merge commit. A tag that already exists is left alone.
 */
@Component
class MergeTagHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MergeTagHandler.class);
  private static final Pattern RUN_BRANCH = Pattern.compile("^emitkit/run-([0-9A-Z]{26})$");

  private final GenerationRunRepository runs;
  private final ProjectConfigRepository configs;
  private final VcsProvider vcsProvider;

  MergeTagHandler(
      GenerationRunRepository runs, ProjectConfigRepository configs, VcsProvider vcsProvider) {
    this.runs = runs;
    this.configs = configs;
    this.vcsProvider = vcsProvider;
  }

  WebhookOutcome handle(Project project, WebhookEvent event) {
    JsonNode payload = event.payload();
    if (!"closed".equals(payload.path("action").asStringOpt().orElse(null))) {
      return WebhookOutcome.ignored("action");
    }
    JsonNode pull = payload.path("pull_request");
    if (!pull.path("merged").asBoolean()) {
      return WebhookOutcome.ignored("not-merged");
    }
    Matcher branch = RUN_BRANCH.matcher(pull.path("head").path("ref").asStringOpt().orElse(""));
    if (!branch.matches()) {
      return WebhookOutcome.ignored("branch");
    }
    // Tags go to the repository the run's output lives in. A pull request on the other repository
    // is not this run's merge, whichever project it belongs to.
    String repo = payload.path("repository").path("full_name").asStringOpt().orElseThrow();
    String target =
        project.getOutputMode() == OutputMode.SEPARATE
            ? project.getOutputRepoFullName()
            : project.getRepoFullName();
    if (!repo.equals(target)) {
      return WebhookOutcome.ignored("repo");
    }
    // A run of another project reads as an unknown run, so the run id's existence stays hidden.
    GenerationRun run = runs.findById(branch.group(1)).orElse(null);
    if (run == null || !run.getProjectId().equals(project.getId())) {
      return WebhookOutcome.ignored("run-not-found");
    }
    if (run.getSdkVersion() == null) {
      return WebhookOutcome.ignored("no-version");
    }
    String sha = pull.path("merge_commit_sha").asStringOpt().filter(s -> !s.isBlank()).orElse(null);
    if (sha == null) {
      return WebhookOutcome.ignored("no-merge-commit");
    }
    List<String> tags = tagsFor(run);
    VcsClient vcs = vcsProvider.forProject(project.getCreatedBy());
    boolean created = false;
    for (String tag : tags) {
      if (vcs.createTag(repo, tag, sha)) {
        created = true;
      } else {
        LOG.info("Tag {} already exists in {}; left as it is", forLog(tag), forLog(repo));
      }
    }
    return created ? WebhookOutcome.tagged() : WebhookOutcome.ignored("tag-exists");
  }

  /** The tags a run's version earns: {@code sdk/v}, plus {@code mcp/v} when MCP is an output. */
  private List<String> tagsFor(GenerationRun run) {
    ProjectConfig config =
        configs
            .findById(run.getConfigId())
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "run config not found"));
    List<String> tags = new ArrayList<>();
    tags.add("sdk/v" + run.getSdkVersion());
    if (config.getOutputs().contains(OutputKind.MCP)) {
      tags.add("mcp/v" + run.getSdkVersion());
    }
    return tags;
  }
}

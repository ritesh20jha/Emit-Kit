package dev.emitkit.api.runs;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import dev.emitkit.api.access.Permission;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.FileStatus;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunArtifact;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.storage.ArtifactStorage;
import dev.emitkit.domain.storage.NoSuchArtifactException;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.Severity;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The files of a run: its manifest, one file read or diffed, its zipped artifact and its lint
 * findings. Each call checks {@link Permission#RUN_VIEW} on the run's project first.
 */
@Service
public class RunFilesService {

  /** The largest file the file view returns. A larger entry is refused, not read into memory. */
  static final int MAX_FILE_BYTES = 1024 * 1024;

  /** Unified diffs show this many unchanged lines around each change. */
  static final int DIFF_CONTEXT = 3;

  /** The hunk header of a change to an empty file, as java-diff-utils writes it. */
  private static final String EMPTY_START = "@@ -1,0";

  private static final String ARTIFACT_EXPIRED = "Artifact expired or not created";

  private final RunService runs;
  private final RunArtifactRepository artifacts;
  private final LintResultRepository lintResults;
  private final ProjectRepository projects;
  private final ArtifactStorage storage;
  private final VcsProvider vcsProvider;

  RunFilesService(
      RunService runs,
      RunArtifactRepository artifacts,
      LintResultRepository lintResults,
      ProjectRepository projects,
      ArtifactStorage storage,
      VcsProvider vcsProvider) {
    this.runs = runs;
    this.artifacts = artifacts;
    this.lintResults = lintResults;
    this.projects = projects;
    this.storage = storage;
    this.vcsProvider = vcsProvider;
  }

  /** The manifest of a run's artifact, or an empty list when the run has no artifact. */
  @Transactional(readOnly = true)
  public List<ManifestEntry> listFiles(String runId) {
    GenerationRun run = runs.require(runId, Permission.RUN_VIEW);
    return artifacts
        .findByRunId(run.getId())
        .map(artifact ->
            artifact.getManifest().stream()
                .map(m -> new ManifestEntry(m.path(), m.sha256(), m.size(), m.status()))
                .toList())
        .orElse(List.of());
  }

  /**
   * One generated file: its content, or with {@code diff} its unified diff against the file on the
   * target branch. The path is checked before the run is looked up.
   */
  public FileView file(String runId, String captured, boolean diff) {
    String path = relativePath(captured);
    GenerationRun run = runs.require(runId, Permission.RUN_VIEW);
    RunArtifact artifact =
        artifacts
            .findByRunId(run.getId())
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "file not in run manifest"));
    dev.emitkit.domain.ManifestEntry entry =
        artifact.getManifest().stream()
            .filter(m -> m.path().equals(path))
            .findFirst()
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "file not in run manifest"));
    String content = readEntry(artifact, path);
    if (!diff) {
      return new FileView(path, entry.status(), content, null);
    }
    String base = baseContent(run, entry.status(), path);
    return new FileView(path, entry.status(), null, unifiedDiff(path, base, content));
  }

  /** The zipped artifact of a run, as a stream the caller must close. */
  public InputStream download(String runId) {
    GenerationRun run = runs.require(runId, Permission.RUN_VIEW);
    RunArtifact artifact =
        artifacts
            .findByRunId(run.getId())
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, ARTIFACT_EXPIRED));
    return open(artifact);
  }

  /** The lint findings of a run. The stored findings keep no line, so every issue has none. */
  @Transactional(readOnly = true)
  public List<LintIssue> lint(String runId) {
    GenerationRun run = runs.require(runId, Permission.RUN_VIEW);
    return lintResults.findByRunId(run.getId()).stream()
        .map(l ->
            new LintIssue(
                l.getRuleId(),
                Severity.valueOf(l.getSeverity().name()),
                l.getJsonPath(),
                null,
                l.getMessage()))
        .toList();
  }

  /**
   * The path a request names, with the one slash the URL pattern leaves in front of it removed.
   * What remains must be a relative path inside the run: no empty name, no absolute path, no
   * backslash and no {@code ..} segment.
   */
  static String relativePath(String captured) {
    String path = captured.startsWith("/") ? captured.substring(1) : captured;
    boolean unsafe =
        path.isEmpty()
            || path.startsWith("/")
            || path.indexOf('\\') >= 0
            || List.of(path.split("/", -1)).contains("..");
    if (unsafe) {
      throw new AppException(ErrorCode.VALIDATION_FAILED, "path must be relative to the run");
    }
    return path;
  }

  /** Opens the artifact's zip, or NOT_FOUND when its object is gone. */
  private InputStream open(RunArtifact artifact) {
    try {
      return storage.get(artifact.getStorageKey());
    } catch (NoSuchArtifactException e) {
      throw new AppException(ErrorCode.NOT_FOUND, ARTIFACT_EXPIRED);
    }
  }

  /** The text of one entry, read through the zip without extracting the archive. */
  private String readEntry(RunArtifact artifact, String path) {
    try (ZipInputStream zip = new ZipInputStream(open(artifact))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.getName().equals(path)) {
          byte[] bytes = zip.readNBytes(MAX_FILE_BYTES + 1);
          if (bytes.length > MAX_FILE_BYTES) {
            throw new AppException(
                ErrorCode.VALIDATION_FAILED,
                "file too large to preview; download the artifact instead");
          }
          return new String(bytes, StandardCharsets.UTF_8);
        }
      }
    } catch (IOException e) {
      throw new AppException(ErrorCode.STORAGE_ERROR, "artifact could not be read", Map.of(), e);
    }
    throw new AppException(ErrorCode.NOT_FOUND, "file not in artifact");
  }

  /**
   * The file on the target branch that the run's output replaces, or null when there is none. An
   * added file has no base; neither has a file the VCS does not hold.
   */
  private @Nullable String baseContent(GenerationRun run, FileStatus status, String path) {
    if (status == FileStatus.ADDED) {
      return null;
    }
    Project project =
        projects
            .findById(run.getProjectId())
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "project not found"));
    String repo =
        project.getOutputMode() == OutputMode.SEPARATE
            ? project.getOutputRepoFullName()
            : project.getRepoFullName();
    if (repo == null) {
      return null;
    }
    try {
      return vcsProvider
          .forProject(project.getCreatedBy())
          .getFile(repo, path, project.getDefaultBranch())
          .content();
    } catch (VcsNotFoundException e) {
      return null;
    }
  }

  /**
   * A unified diff from the base to the new content, with headers naming the path on both sides.
   */
  static String unifiedDiff(String path, @Nullable String base, String content) {
    List<String> original = base == null ? List.of() : base.lines().toList();
    List<String> revised = content.lines().toList();
    List<String> lines =
        UnifiedDiffUtils.generateUnifiedDiff(
            "a/" + path, "b/" + path, original, DiffUtils.diff(original, revised), DIFF_CONTEXT);
    // java-diff-utils starts an empty range at line 1; the unified format writes that start as 0.
    return lines.stream()
        .map(line -> original.isEmpty() ? startAtZero(line) : line)
        .collect(Collectors.joining("\n"));
  }

  /** Rewrites a hunk header that starts an empty range at line 1 to start it at line 0. */
  private static String startAtZero(String line) {
    return line.startsWith(EMPTY_START + " ")
        ? "@@ -0,0" + line.substring(EMPTY_START.length())
        : line;
  }
}

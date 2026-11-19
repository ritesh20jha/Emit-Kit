package dev.emitkit.worker.steps;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.domain.ManifestEntry;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.RunArtifact;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.storage.ArtifactStorage;
import dev.emitkit.domain.storage.StorageException;
import dev.emitkit.vcs.VcsRepo;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Zips the run's files, uploads the zip and records the artifact with its manifest (spec v1 §5.1
 * step 9). Each file is compared with the same path on the target branch: the project repo for
 * APPEND, the output repo for SEPARATE.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class PackageArtifactStep implements PipelineStep {

  /**
   * The zip entry time: 1980-01-01 00:00 in the server's zone. Zip stores the local fields, so
   * every server writes the same bytes for the same files.
   */
  private static final long FIXED_ZIP_TIME =
      LocalDateTime.of(1980, 1, 1, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

  private final WorkDir workDir;
  private final ArtifactStorage storage;
  private final RunArtifactRepository artifacts;

  @Autowired
  public PackageArtifactStep(
      WorkDir workDir, ArtifactStorage storage, RunArtifactRepository artifacts) {
    this.workDir = workDir;
    this.storage = storage;
    this.artifacts = artifacts;
  }

  @Override
  public StepName name() {
    return StepName.PACKAGE_ARTIFACT;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    String runId = ctx.run().getId();
    Project project = ctx.project();
    List<WorkDir.Entry> files = workDir.manifest(runId);
    Map<String, String> contents = readContents(runId, files);
    ctx.checkCancelled();

    Target target = target(project, ctx);
    Map<String, String> targetBlobs = ctx.vcs().listBlobShas(target.repo(), target.branch(), "");
    List<ManifestBuilder.Planned> planned = ManifestBuilder.plan(files, contents, targetBlobs);
    ctx.checkCancelled();

    String key = "artifacts/" + project.getOrgId() + "/" + runId + ".zip";
    Path zip = createTempZip();
    try {
      long sizeBytes = zip(planned, zip);
      ctx.checkCancelled();
      upload(key, zip);
      save(runId, key, sizeBytes, planned);
      ctx.log().info("packaged " + planned.size() + " files, " + sizeBytes + " bytes");
      return new StepResult.Continue(new Output(key, planned.size(), sizeBytes));
    } finally {
      deleteQuietly(zip);
    }
  }

  /** What the artifact was saved as: its storage key, its file count and its zip size. */
  public record Output(String storageKey, int fileCount, long sizeBytes) {}

  private Map<String, String> readContents(String runId, List<WorkDir.Entry> files) {
    Path filesDir = workDir.filesDir(runId);
    Map<String, String> contents = new TreeMap<>();
    try {
      for (WorkDir.Entry file : files) {
        contents.put(
            file.path(), Files.readString(filesDir.resolve(file.path()), StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new TransientException(
          ErrorCode.STORAGE_ERROR, "generated files unreadable", Map.of(), e);
    }
    return contents;
  }

  private static Path createTempZip() {
    try {
      return Files.createTempFile("artifact-", ".zip");
    } catch (IOException e) {
      throw new TransientException(
          ErrorCode.STORAGE_ERROR, "artifact temp file unavailable", Map.of(), e);
    }
  }

  /** The repo and branch the artifact is compared with. */
  private record Target(String repo, String branch) {}

  private static Target target(Project project, RunContext ctx) {
    if (project.getOutputMode() != OutputMode.SEPARATE) {
      return new Target(project.getRepoFullName(), project.getDefaultBranch());
    }
    String repo = project.getOutputRepoFullName();
    return new Target(repo, defaultBranch(ctx, repo));
  }

  /** The default branch of the output repo, from the repos the identity can see. */
  private static String defaultBranch(RunContext ctx, String repo) {
    Optional<VcsRepo> found =
        ctx.vcs().listRepos().stream().filter(r -> r.fullName().equals(repo)).findFirst();
    return found
        .map(VcsRepo::defaultBranch)
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "output repo not found: " + repo));
  }

  /**
   * Writes the zip of every file that is not skipped, in path order, with fixed entry times.
   * Returns the zip's size in bytes.
   */
  private static long zip(List<ManifestBuilder.Planned> planned, Path target) {
    try {
      try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
        out.setLevel(Deflater.DEFAULT_COMPRESSION);
        for (ManifestBuilder.Planned file : planned) {
          if (!file.zipped()) {
            continue;
          }
          ZipEntry entry = new ZipEntry(file.entry().path());
          entry.setTime(FIXED_ZIP_TIME);
          out.putNextEntry(entry);
          out.write(file.content().getBytes(StandardCharsets.UTF_8));
          out.closeEntry();
        }
      }
      return Files.size(target);
    } catch (IOException e) {
      throw new TransientException(
          ErrorCode.STORAGE_ERROR, "artifact could not be zipped", Map.of(), e);
    }
  }

  private void upload(String key, Path zip) {
    try {
      storage.put(key, zip);
    } catch (StorageException e) {
      throw new TransientException(ErrorCode.STORAGE_ERROR, "artifact upload failed", Map.of(), e);
    }
  }

  /** Replaces the run's artifact row, keeping its id, with the new manifest. */
  private void save(
      String runId, String key, long sizeBytes, List<ManifestBuilder.Planned> planned) {
    List<ManifestEntry> manifest = planned.stream().map(ManifestBuilder.Planned::entry).toList();
    Optional<RunArtifact> previous = artifacts.findByRunId(runId);
    previous.ifPresent(artifacts::delete);
    // The delete must reach the database before the insert, which reuses the id.
    artifacts.flush();
    artifacts.save(
        new RunArtifact(
            previous.map(RunArtifact::getId).orElse(null),
            runId,
            key,
            sizeBytes,
            manifest.size(),
            manifest,
            null));
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      // A leftover temp file is harmless; the next run uses a new name.
    }
  }
}

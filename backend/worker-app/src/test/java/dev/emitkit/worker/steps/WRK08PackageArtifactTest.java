package dev.emitkit.worker.steps;

import static dev.emitkit.domain.FileStatus.ADDED;
import static dev.emitkit.domain.FileStatus.MODIFIED;
import static dev.emitkit.domain.FileStatus.SKIPPED;
import static dev.emitkit.domain.FileStatus.UNCHANGED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.common.id.Ids;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.ManifestEntry;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.RunArtifact;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.domain.storage.ArtifactStorage;
import dev.emitkit.domain.storage.StorageException;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PACKAGE_ARTIFACT against an in-memory VCS, a work dir on disk and fake storage and run-artifact
 * rows. No database, no Docker.
 */
class WRK08PackageArtifactTest {

  private static final String ORG = "org-1";
  private static final String USER = "user-1";
  private static final String REPO = "acme/api";
  private static final String OUT_REPO = "acme/out";
  private static final String CONFIG_ID = Ids.newUlid();
  private static final String RUN_ID = Ids.newUlid();
  private static final String STORAGE_KEY = "artifacts/" + ORG + "/" + RUN_ID + ".zip";

  @TempDir Path root;

  private InMemoryVcsClient vcs;
  private WorkDir workDir;
  private FakeStorage storage;
  private FakeArtifacts artifacts;
  private CancellationRegistry cancellation;
  private PackageArtifactStep step;
  private Project project;

  @BeforeEach
  void setUp() {
    vcs = new InMemoryVcsClient();
    vcs.createRepo("acme", "api", false);
    vcs.createRepo("acme", "out", false);
    workDir = new WorkDir(root);
    storage = new FakeStorage();
    artifacts = new FakeArtifacts();
    cancellation =
        new CancellationRegistry(
            (GenerationRunRepository)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {GenerationRunRepository.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("findById")) {
                        return Optional.empty();
                      }
                      throw new UnsupportedOperationException(method.getName());
                    }));
    step = new PackageArtifactStep(workDir, storage, artifacts.repo());
    project = project(OutputMode.APPEND);
  }

  @Test
  void manifestStatuses() throws Exception {
    seed(REPO, "sdk/same.ts", "same");
    seed(REPO, "sdk/diff.ts", "old");
    generate(
        new GeneratedFile("sdk/same.ts", "same", FileKind.GENERATED),
        new GeneratedFile("sdk/diff.ts", "new", FileKind.GENERATED),
        new GeneratedFile("sdk/added.ts", "fresh", FileKind.GENERATED));

    runStep();

    RunArtifact artifact = savedArtifact();
    assertThat(artifact.getManifest())
        .containsExactly(
            new ManifestEntry("sdk/added.ts", sha256("fresh"), 5, ADDED),
            new ManifestEntry("sdk/diff.ts", sha256("new"), 3, MODIFIED),
            new ManifestEntry("sdk/same.ts", sha256("same"), 4, UNCHANGED));
    assertThat(artifact.getFileCount()).isEqualTo(3);
    assertThat(artifact.getStorageKey()).isEqualTo(STORAGE_KEY);
    // UNCHANGED files are zipped too: only SKIPPED files stay out of the package.
    byte[] zip = storage.objects.get(STORAGE_KEY);
    assertThat(artifact.getSizeBytes()).isEqualTo(zip.length);
    assertThat(unzip(zip))
        .containsExactly(
            Map.entry("sdk/added.ts", "fresh"),
            Map.entry("sdk/diff.ts", "new"),
            Map.entry("sdk/same.ts", "same"));
  }

  @Test
  void existingCustomStub_isSkipped_andNotZipped() throws Exception {
    seed(REPO, "sdk/custom/Stub.ts", "user edit");
    generate(
        new GeneratedFile("sdk/custom/Stub.ts", "stub", FileKind.CUSTOM_STUB),
        new GeneratedFile("sdk/custom/Fresh.ts", "fresh stub", FileKind.CUSTOM_STUB),
        new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));

    runStep();

    // The skipped file stays in the manifest with the hash of what the generator wrote.
    assertThat(savedArtifact().getManifest())
        .containsExactly(
            new ManifestEntry("sdk/custom/Fresh.ts", sha256("fresh stub"), 10, ADDED),
            new ManifestEntry("sdk/custom/Stub.ts", sha256("stub"), 4, SKIPPED),
            new ManifestEntry("sdk/index.ts", sha256("index"), 5, ADDED));
    assertThat(unzip(storage.objects.get(STORAGE_KEY)))
        .containsOnlyKeys("sdk/custom/Fresh.ts", "sdk/index.ts");
  }

  @Test
  void zipIsDeterministic() throws Exception {
    seed(REPO, "sdk/old.ts", "old");
    GeneratedFile b = new GeneratedFile("sdk/b.ts", "bee", FileKind.GENERATED);
    GeneratedFile a = new GeneratedFile("sdk/a.ts", "ay", FileKind.GENERATED);
    GeneratedFile stub = new GeneratedFile("sdk/c/stub.ts", "stub", FileKind.CUSTOM_STUB);
    workDir.write(RUN_ID, "gen-1", List.of(b, a, stub));
    runStep();
    byte[] first = storage.objects.get(STORAGE_KEY);

    // The same files written in the reverse order, packaged again. The second run replaces the
    // first run's artifact row rather than adding one.
    List<GeneratedFile> reversed = new ArrayList<>(List.of(b, a, stub));
    Collections.reverse(reversed);
    workDir.write(RUN_ID, "gen-1", reversed);
    runStep();

    assertThat(storage.objects.get(STORAGE_KEY)).isEqualTo(first);
    assertThat(artifacts.rows).hasSize(1);
  }

  @Test
  void storageDown_isTransient() throws Exception {
    generate(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));
    storage.failWith(new StorageException("storage returned 503", null));

    assertThatThrownBy(this::runStep)
        .isInstanceOfSatisfying(
            TransientException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_ERROR));
    // Nothing is saved for a package that did not reach storage.
    assertThat(artifacts.rows).isEmpty();
  }

  @Test
  void separateMode_comparesWithOutputRepo() throws Exception {
    seed(REPO, "sdk/a.ts", "generated");
    seed(OUT_REPO, "sdk/a.ts", "stale");
    generate(new GeneratedFile("sdk/a.ts", "generated", FileKind.GENERATED));
    project = project(OutputMode.SEPARATE);

    runStep();

    // The file matches the project repo but not the output repo, so it is compared with the output.
    assertThat(savedArtifact().getManifest())
        .containsExactly(new ManifestEntry("sdk/a.ts", sha256("generated"), 9, MODIFIED));
    assertThat(vcs.calls("listBlobShas"))
        .extracting(InMemoryVcsClient.Call::repo)
        .containsExactly(OUT_REPO);
  }

  private Project project(OutputMode mode) {
    Project created = new Project(null, ORG, REPO, "openapi.yaml", "main", mode, USER);
    if (mode == OutputMode.SEPARATE) {
      created.setOutputRepoFullName(OUT_REPO);
    }
    return created;
  }

  private void seed(String repo, String path, String content) {
    vcs.commitTree(repo, "main", List.of(new CommitFile(path, content)), "seed");
  }

  private void generate(GeneratedFile... files) {
    workDir.write(RUN_ID, "gen-1", List.of(files));
  }

  /** Runs the step and returns its output, the record it saved. */
  private Object runStep() throws Exception {
    return ((StepResult.Continue) step.execute(context())).output();
  }

  private RunArtifact savedArtifact() {
    return artifacts.repo().findByRunId(RUN_ID).orElseThrow();
  }

  private RunContext context() {
    GenerationRun run =
        new GenerationRun(RUN_ID, project.getId(), CONFIG_ID, RunTrigger.MANUAL, false, null);
    ProjectConfig config =
        new ProjectConfig(CONFIG_ID, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, USER);
    Supplier<VcsClient> vcsSupplier = () -> vcs;
    try {
      Constructor<RunContext> ctor =
          RunContext.class.getDeclaredConstructor(
              GenerationRun.class,
              Project.class,
              ProjectConfig.class,
              Supplier.class,
              RunLogger.class,
              CancellationRegistry.class);
      ctor.setAccessible(true);
      return ctor.newInstance(run, project, config, vcsSupplier, RunLogger.NOOP, cancellation);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("RunContext could not be built for the test", e);
    }
  }

  private static String sha256(String content) throws NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(UTF_8)));
  }

  /** The entries of a zip, by name, with their text content. */
  private static Map<String, String> unzip(byte[] zip) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        files.put(entry.getName(), new String(in.readAllBytes(), UTF_8));
      }
    }
    return files;
  }

  /** Keeps each uploaded object's bytes. Every put fails once {@link #failWith} is called. */
  private static final class FakeStorage implements ArtifactStorage {

    final Map<String, byte[]> objects = new LinkedHashMap<>();
    private RuntimeException failure;

    void failWith(RuntimeException error) {
      failure = error;
    }

    @Override
    public void put(String key, Path file) {
      if (failure != null) {
        throw failure;
      }
      try {
        objects.put(key, Files.readAllBytes(file));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public InputStream get(String key) {
      throw new UnsupportedOperationException("get");
    }

    @Override
    public void delete(String key) {
      throw new UnsupportedOperationException("delete");
    }

    @Override
    public List<String> listOlderThan(String prefix, Instant cutoff) {
      throw new UnsupportedOperationException("listOlderThan");
    }
  }

  /** The run_artifacts rows, served through the repository calls the step makes. */
  private static final class FakeArtifacts {

    final Map<String, RunArtifact> rows = new LinkedHashMap<>();

    RunArtifactRepository repo() {
      return (RunArtifactRepository)
          Proxy.newProxyInstance(
              FakeArtifacts.class.getClassLoader(),
              new Class<?>[] {RunArtifactRepository.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "findByRunId" ->
                        rows.values().stream()
                            .filter(artifact -> artifact.getRunId().equals(args[0]))
                            .findFirst();
                    case "save", "saveAndFlush" -> {
                      RunArtifact artifact = (RunArtifact) args[0];
                      rows.put(artifact.getId(), artifact);
                      yield artifact;
                    }
                    case "delete" -> {
                      rows.remove(((RunArtifact) args[0]).getId());
                      yield null;
                    }
                    case "flush" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }
  }
}

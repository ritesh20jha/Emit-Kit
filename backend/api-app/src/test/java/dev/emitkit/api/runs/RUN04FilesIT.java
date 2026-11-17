package dev.emitkit.api.runs;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.VIEWER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.ChangeType;
import dev.emitkit.domain.FileStatus;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.LintResult;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.ManifestEntry;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.RunArtifact;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.SdkVersion;
import dev.emitkit.domain.SdkVersionRepository;
import dev.emitkit.domain.Severity;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.domain.storage.ArtifactStorage;
import dev.emitkit.domain.storage.NoSuchArtifactException;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsNotFoundException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * RUN-04: a run's file manifest, its files and their diffs, its artifact download, lint results and
 * the versions of a project. Runs against Postgres and Redis, with the artifact store held in
 * memory and the VCS provider replaced by the in-memory fake.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class RUN04FilesIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class TestDoubles {

    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }

    @Bean
    @Primary
    MemoryArtifacts memoryArtifacts() {
      return new MemoryArtifacts();
    }
  }

  /** Keeps artifact bytes in a map, and reports a missing key as the S3 store does. */
  static class MemoryArtifacts implements ArtifactStorage {

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Path file) {
      throw new UnsupportedOperationException("tests store bytes through seedArtifact");
    }

    @Override
    public InputStream get(String key) {
      byte[] bytes = objects.get(key);
      if (bytes == null) {
        throw new NoSuchArtifactException(key, null);
      }
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void delete(String key) {
      objects.remove(key);
    }

    @Override
    public List<String> listOlderThan(String prefix, Instant cutoff) {
      return List.of();
    }
  }

  private static final String FILE = "/api/v1/runs/{runId}/files";
  private static final String ARTIFACT = "/api/v1/runs/{runId}/artifact";
  private static final String LINT = "/api/v1/runs/{runId}/lint";
  private static final String VERSIONS = "/api/v1/projects/{id}/versions";
  private static final String REPO = "acme/widgets";
  private static final String SEPARATE_REPO = "acme/widgets-sdk";
  private static final String CLIENT = "sdk/typescript/src/client.ts";
  private static final String NEW_FILE = "sdk/typescript/src/new.ts";
  private static final String INDEX = "sdk/typescript/src/index.ts";
  private static final String BASE_CLIENT = "a\nb\nc\n";
  private static final String GENERATED_CLIENT = "a\nB\nc\n";
  private static final String NEW_CONTENT = "export const x = 1;\nexport const y = 2;\n";
  private static final String INDEX_CONTENT = "export * from './client';\n";
  private static final Map<String, String> GENERATED =
      Map.of(CLIENT, GENERATED_CLIENT, NEW_FILE, NEW_CONTENT, INDEX, INDEX_CONTENT);
  private static final List<ManifestEntry> MANIFEST =
      List.of(
          entry(CLIENT, GENERATED_CLIENT, FileStatus.MODIFIED),
          entry(NEW_FILE, NEW_CONTENT, FileStatus.ADDED),
          entry(INDEX, INDEX_CONTENT, FileStatus.UNCHANGED));
  private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private MemoryArtifacts memory;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository generationRuns;
  @Autowired private RunArtifactRepository artifacts;
  @Autowired private LintResultRepository lintResults;
  @Autowired private SdkVersionRepository sdkVersions;
  @Autowired private OAuthAccountRepository oauth;

  @MockitoBean private VcsProvider vcsProvider;

  private InMemoryVcsClient vcs;

  @BeforeEach
  void resetData() {
    sdkVersions.deleteAll();
    lintResults.deleteAll();
    artifacts.deleteAll();
    generationRuns.deleteAllInBatch();
    configs.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
    memory.objects.clear();
    vcs = new InMemoryVcsClient();
    when(vcsProvider.forProject(anyString())).thenReturn(vcs);
  }

  @Test
  void manifestListed() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    seedArtifact(run, GENERATED, MANIFEST);

    mvc.perform(get(FILE, run.getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].path").value(contains(CLIENT, NEW_FILE, INDEX)))
        .andExpect(jsonPath("$[*].status").value(contains("MODIFIED", "ADDED", "UNCHANGED")))
        .andExpect(jsonPath("$[0].size").value(GENERATED_CLIENT.length()));

    // A run with no artifact row has an empty manifest, not an error.
    GenerationRun bare = run(f.project(), f.owner());
    mvc.perform(get(FILE, bare.getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void fileContent_readsEntryFromZip() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    seedArtifact(run, GENERATED, MANIFEST);

    mvc.perform(get(FILE + "/" + CLIENT, run.getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MODIFIED"))
        .andExpect(jsonPath("$.content").value(GENERATED_CLIENT))
        .andExpect(jsonPath("$.unifiedDiff").value(nullValue()));

    // An entry past the 1 MiB preview limit is refused, not read into memory.
    String huge = "x".repeat(RunFilesService.MAX_FILE_BYTES + 1);
    GenerationRun big = run(f.project(), f.owner());
    seedArtifact(big, Map.of("big.ts", huge), List.of(entry("big.ts", huge, FileStatus.ADDED)));
    mvc.perform(get(FILE + "/big.ts", big.getId()).with(signedInAs(f.owner())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(
            jsonPath("$.detail").value("file too large to preview; download the artifact instead"));

    // A path outside the manifest is a 404.
    mvc.perform(
            get(FILE + "/sdk/typescript/src/missing.ts", run.getId()).with(signedInAs(f.owner())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void modifiedFile_diffAgainstBase() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    seedArtifact(run, GENERATED, MANIFEST);

    assertThat(diffOf(f.owner(), run, CLIENT))
        .isEqualTo(unified(CLIENT, "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c"));
    assertThat(vcs.calls("getFile")).hasSize(1);
  }

  /** An added file diffs against empty and fetches nothing; a base the VCS lacks does too. */
  @Test
  void addedFile_andMissingBase_diffAgainstEmpty() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    seedArtifact(run, GENERATED, MANIFEST);

    assertThat(diffOf(f.owner(), run, NEW_FILE))
        .isEqualTo(
            unified(NEW_FILE, "@@ -0,0 +1,2 @@\n+export const x = 1;\n+export const y = 2;"));
    assertThat(vcs.calls("getFile")).isEmpty();

    vcs.failNext("getFile", new VcsNotFoundException("file not found: " + CLIENT));
    assertThat(diffOf(f.owner(), run, CLIENT))
        .isEqualTo(unified(CLIENT, "@@ -0,0 +1,3 @@\n+a\n+B\n+c"));
  }

  /** A separate-output project diffs against its output repo, not the source repo. */
  @Test
  void separateOutput_diffsAgainstOutputRepo() throws Exception {
    Fixture f = fixture();
    Project separate = project(f.org(), f.owner(), OutputMode.SEPARATE, "acme/api-src");
    vcs.putFile(SEPARATE_REPO, CLIENT, "a\nQ\nc\n", null, "output base", "main");
    GenerationRun run = run(separate, f.owner());
    seedArtifact(run, GENERATED, MANIFEST);

    assertThat(diffOf(f.owner(), run, CLIENT)).contains("-Q\n+B");
  }

  /**
   * Traversal, backslash and encoded-separator paths never reach the run. The request firewall
   * rejects them, and the rejected-request handler answers with problem+json VALIDATION_FAILED. The
   * path check behind it refuses the rest, and is asserted directly because MockMvc collapses a
   * doubled slash.
   */
  @Test
  void pathTraversal_400() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    seedArtifact(run, GENERATED, MANIFEST);

    for (String bad :
        List.of(
            "sdk/../../secret",
            "sdk\\typescript\\client.ts",
            "sdk/%2e%2e/secret",
            "sdk%2F..%2Fsecret")) {
      mvc.perform(get(FILE + "/" + bad, run.getId()).with(signedInAs(f.owner())))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    assertThat(vcs.calls("getFile")).isEmpty();

    for (String captured : List.of("/", "//etc/passwd", "/sdk/../x", "/sdk\\x", "/..")) {
      assertThatThrownBy(() -> RunFilesService.relativePath(captured))
          .isInstanceOfSatisfying(
              AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    assertThat(RunFilesService.relativePath("/sdk/typescript/client.ts"))
        .isEqualTo("sdk/typescript/client.ts");
  }

  @Test
  void artifactStreamsZip() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    byte[] zip = seedArtifact(run, GENERATED, MANIFEST);

    MvcResult started =
        mvc.perform(get(ARTIFACT, run.getId()).with(signedInAs(f.owner())))
            .andExpect(request().asyncStarted())
            .andReturn();
    MvcResult done =
        mvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/zip"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        "attachment; filename=\"emitkit-" + run.getId() + ".zip\""))
            .andReturn();

    assertThat(done.getResponse().getContentAsByteArray()).isEqualTo(zip);
  }

  /** No artifact row, or a row whose stored object is gone, is the same 404 with a problem body. */
  @Test
  void expiredArtifact_404() throws Exception {
    Fixture f = fixture();
    GenerationRun bare = run(f.project(), f.owner());
    GenerationRun gone = run(f.project(), f.owner());
    artifacts.saveAndFlush(
        new RunArtifact(null, gone.getId(), "artifacts/acme/gone.zip", 0, 0, List.of(), BASE));

    for (GenerationRun run : List.of(bare, gone)) {
      mvc.perform(get(ARTIFACT, run.getId()).with(signedInAs(f.owner())))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"))
          .andExpect(jsonPath("$.detail").value("Artifact expired or not created"));
    }
  }

  @Test
  void versionsNewestFirst() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    release(f.project(), "1.0.0", run, ChangeType.MAJOR, "First", "2026-01-01T00:00:00Z");
    release(f.project(), "1.1.0", run, ChangeType.MINOR, "Feature", "2026-02-01T00:00:00Z");
    release(f.project(), "1.1.1", run, ChangeType.PATCH, "Fix", "2026-03-01T00:00:00Z");

    mvc.perform(get(VERSIONS, f.project().getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].version").value(contains("1.1.1", "1.1.0", "1.0.0")))
        .andExpect(jsonPath("$[0].changeType").value("PATCH"))
        .andExpect(jsonPath("$[0].runId").value(run.getId()))
        .andExpect(jsonPath("$[0].changelog").value("Fix"))
        .andExpect(jsonPath("$[0].createdAt").value("2026-03-01T00:00:00Z"));
  }

  @Test
  void lintResults_listedWithoutLine() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f.project(), f.owner());
    lintResults.saveAndFlush(
        new LintResult(
            null, run.getId(), "operation-id", Severity.ERROR, "$.paths./pets.get", "Missing id"));
    lintResults.saveAndFlush(
        new LintResult(null, run.getId(), "info-title", Severity.WARNING, "$.info", "Short"));

    mvc.perform(get(LINT, run.getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].ruleId").value(containsInAnyOrder("operation-id", "info-title")))
        .andExpect(jsonPath("$[*].line").value(everyItem(nullValue())))
        .andExpect(
            jsonPath("$[?(@.ruleId=='operation-id')].jsonPath")
                .value(contains("$.paths./pets.get")))
        .andExpect(
            jsonPath("$[?(@.ruleId=='operation-id')].message").value(contains("Missing id")));
  }

  /** A viewer may read; a stranger gets the same 404 an unknown run or project would. */
  @Test
  void viewerReads_nonMemberGets404() throws Exception {
    Fixture f = fixture();
    User viewer = addMember(f.org(), 2, "viewer", VIEWER);
    GenerationRun run = run(f.project(), f.owner());
    seedArtifact(run, GENERATED, MANIFEST);
    User stranger =
        users.save(new User(null, 9, "stranger", null, null, "https://avatars.example.com/u/9"));

    mvc.perform(get(FILE, run.getId()).with(signedInAs(viewer))).andExpect(status().isOk());

    for (String url : List.of(FILE, FILE + "/" + CLIENT, ARTIFACT, LINT)) {
      mvc.perform(get(url, run.getId()).with(signedInAs(stranger)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"))
          .andExpect(jsonPath("$.detail").value("run not found"));
    }
    mvc.perform(get(VERSIONS, f.project().getId()).with(signedInAs(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("project not found"));
  }

  /** The owner of an organization, and the project on the source repo. */
  private record Fixture(Organization org, User owner, Project project) {}

  private Fixture fixture() {
    Organization acme =
        organizations.save(new Organization(null, null, "vcs-acme", "acme", "acme", false, null));
    User owner = addMember(acme, 1, "owner", ADMIN);
    vcs.createRepo("acme", "widgets", false);
    vcs.putFile(REPO, CLIENT, BASE_CLIENT, null, "base", "main");
    vcs.createRepo("acme", "widgets-sdk", false);
    return new Fixture(acme, owner, project(acme, owner, OutputMode.APPEND, REPO));
  }

  /** A project of the source repo, whose separate output goes to {@link #SEPARATE_REPO}. */
  private Project project(Organization org, User creator, OutputMode mode, String repo) {
    Project project =
        new Project(null, org.getId(), repo, "openapi.yaml", "main", mode, creator.getId());
    project.setOutputRepoFullName(SEPARATE_REPO);
    return projects.saveAndFlush(project);
  }

  /** A successful run of the project at {@link #BASE}, on the project's version-1 config. */
  private GenerationRun run(Project project, User creator) {
    ProjectConfig config =
        configs.findAll().stream()
            .filter(c -> c.getProjectId().equals(project.getId()))
            .findFirst()
            .orElseGet(() ->
                configs.save(
                    new ProjectConfig(
                        null,
                        project.getId(),
                        1,
                        VersionStrategy.EMITKIT_MANAGED,
                        creator.getId())));
    GenerationRun run =
        new GenerationRun(null, project.getId(), config.getId(), RunTrigger.MANUAL, false, BASE);
    run.setStatus(RunStatus.SUCCESS);
    return generationRuns.saveAndFlush(run);
  }

  /** Stores the files as a zip under the run's key, with the artifact row that points at it. */
  private byte[] seedArtifact(
      GenerationRun run, Map<String, String> files, List<ManifestEntry> manifest) {
    byte[] zip = zip(files);
    String key = "artifacts/acme/" + run.getId() + ".zip";
    memory.objects.put(key, zip);
    artifacts.saveAndFlush(
        new RunArtifact(null, run.getId(), key, zip.length, manifest.size(), manifest, BASE));
    return zip;
  }

  private void release(
      Project project, String version, GenerationRun run, ChangeType type, String log, String at) {
    sdkVersions.saveAndFlush(
        new SdkVersion(null, project.getId(), version, run.getId(), type, log, Instant.parse(at)));
  }

  /** The unified diff of one file of the run, as the file endpoint returns it. */
  private String diffOf(User caller, GenerationRun run, String path) throws Exception {
    String body =
        mvc.perform(get(FILE + "/" + path + "?diff=true", run.getId()).with(signedInAs(caller)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.unifiedDiff");
  }

  /** The unified diff text for {@code path} with one hunk, headers included. */
  private static String unified(String path, String hunk) {
    return "--- a/" + path + "\n+++ b/" + path + "\n" + hunk;
  }

  /** Adds a user as a member of an org with the given role. */
  private User addMember(Organization org, long githubId, String login, Role role) {
    User user =
        users.save(
            new User(
                null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, RoleSource.SYNC));
    return user;
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }

  private static ManifestEntry entry(String path, String content, FileStatus status) {
    return new ManifestEntry(path, "sha-" + path, content.length(), status);
  }

  private static byte[] zip(Map<String, String> files) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> file : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(file.getKey()));
        zip.write(file.getValue().getBytes(UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }
}

package dev.emitkit.api.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RabbitTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * HOOK-02: a push to the default branch that changes the spec queues a webhook run, and a merged
 * pull request from a run tags the SDK version (and the MCP version when MCP is an output). Runs
 * against Postgres, Redis and RabbitMQ; the VCS provider is an in-memory fake.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class, RabbitTestConfig.class})
class HOOK02PushMergeIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String PATH = "/webhooks/github";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SECRET = "hook-02-webhook-secret";
  private static final String SPEC_PATH = "openapi.yaml";
  private static final String VERSION = "1.2.3";

  /** One GitHub delivery: its event, the raw JSON body, and the secret it is signed with. */
  private record Delivery(String event, String body, String signingSecret) {}

  /** The status and body the endpoint answered with. */
  private record Response(int status, String body) {}

  @Autowired private MockMvc mvc;
  @Autowired private SecretCipher cipher;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private OrganizationRepository organizations;
  @Autowired private UserRepository users;

  @MockitoBean private VcsProvider vcsProvider;

  private InMemoryVcsClient vcs;

  @BeforeEach
  void fakeVcs() {
    vcs = new InMemoryVcsClient();
    when(vcsProvider.forProject(anyString())).thenReturn(vcs);
  }

  @Test
  void pushToDefaultBranchTouchingSpec_createsOneWebhookRun() throws Exception {
    Project project = connect(newRepo("push"), null, OutputMode.APPEND, List.of(OutputKind.SDK));

    Response response =
        deliver(delivery("push", push(project.getRepoFullName(), "refs/heads/main", SPEC_PATH)));

    assertThat(response.status()).isEqualTo(200);
    List<GenerationRun> created = runsOf(project);
    assertThat(created).hasSize(1);
    GenerationRun run = created.get(0);
    assertThat(run.getTrigger()).isEqualTo(RunTrigger.WEBHOOK);
    assertThat(response.body())
        .isEqualTo("{\"status\":\"queued\",\"runId\":\"" + run.getId() + "\"}");
  }

  @Test
  void pushToOtherBranch_ignored() throws Exception {
    Project project = connect(newRepo("branch"), null, OutputMode.APPEND, List.of(OutputKind.SDK));

    Response response =
        deliver(delivery("push", push(project.getRepoFullName(), "refs/heads/feature", SPEC_PATH)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ignored\",\"reason\":\"branch\"}");
    assertThat(runsOf(project)).isEmpty();
  }

  @Test
  void pushNotTouchingSpec_ignored() throws Exception {
    Project project = connect(newRepo("spec"), null, OutputMode.APPEND, List.of(OutputKind.SDK));

    Response response =
        deliver(delivery("push", push(project.getRepoFullName(), "refs/heads/main", "README.md")));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body())
        .isEqualTo("{\"status\":\"ignored\",\"reason\":\"spec-not-changed\"}");
    assertThat(runsOf(project)).isEmpty();
  }

  @Test
  void mergedEmitkitPr_tagsSdkVersion() throws Exception {
    String repo = newRepo("merge");
    Project project = connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK));
    GenerationRun run = webhookRun(project);
    String merge = commit(repo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(repo, "closed", true, run, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"tagged\"}");
    assertThat(vcs.tags(repo)).containsOnly(entry("sdk/v" + VERSION, merge));
  }

  @Test
  void mergedWithMcpOutput_alsoTagsMcp() throws Exception {
    String repo = newRepo("mcp");
    Project project =
        connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK, OutputKind.MCP));
    GenerationRun run = webhookRun(project);
    String merge = commit(repo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(repo, "closed", true, run, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(vcs.tags(repo))
        .containsOnly(entry("sdk/v" + VERSION, merge), entry("mcp/v" + VERSION, merge));
  }

  @Test
  void tagExists_200() throws Exception {
    String repo = newRepo("exists");
    Project project = connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK));
    GenerationRun run = webhookRun(project);
    String earlier = commit(repo, "earlier");
    vcs.createTag(repo, "sdk/v" + VERSION, earlier);
    String merge = commit(repo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(repo, "closed", true, run, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ignored\",\"reason\":\"tag-exists\"}");
    assertThat(vcs.tags(repo)).containsOnly(entry("sdk/v" + VERSION, earlier));
  }

  @Test
  void closedNotMerged_ignored() throws Exception {
    String repo = newRepo("closed");
    Project project = connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK));
    GenerationRun run = webhookRun(project);
    String head = commit(repo, "abandoned");

    Response response =
        deliver(delivery("pull_request", pullRequest(repo, "closed", false, run, head)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ignored\",\"reason\":\"not-merged\"}");
    assertThat(vcs.tags(repo)).isEmpty();
  }

  @Test
  void separateMode_prOnOutputRepo_isMatched() throws Exception {
    String sourceRepo = newRepo("source");
    String outputRepo = newRepo("output");
    Project project = connect(sourceRepo, outputRepo, OutputMode.SEPARATE, List.of(OutputKind.SDK));
    GenerationRun run = webhookRun(project);
    String merge = commit(outputRepo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(outputRepo, "closed", true, run, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"tagged\"}");
    assertThat(vcs.tags(outputRepo)).containsOnly(entry("sdk/v" + VERSION, merge));
  }

  @Test
  void mergedRunOfAnotherProject_ignored() throws Exception {
    String repo = newRepo("foreign");
    connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK));
    Project owner = connect(newRepo("owner"), null, OutputMode.APPEND, List.of(OutputKind.SDK));
    GenerationRun foreign = webhookRun(owner);
    String merge = commit(repo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(repo, "closed", true, foreign, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ignored\",\"reason\":\"run-not-found\"}");
    assertThat(vcs.calls("createTag")).isEmpty();
    assertThat(vcs.tags(repo)).isEmpty();
  }

  @Test
  void pushToOutputRepo_ignored() throws Exception {
    String sourceRepo = newRepo("source-push");
    String outputRepo = newRepo("output-push");
    Project project = connect(sourceRepo, outputRepo, OutputMode.SEPARATE, List.of(OutputKind.SDK));

    Response response = deliver(delivery("push", push(outputRepo, "refs/heads/main", SPEC_PATH)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ignored\",\"reason\":\"repo\"}");
    assertThat(runsOf(project)).isEmpty();
  }

  @Test
  void mergedBranchOnWrongRepo_ignored() throws Exception {
    String sourceRepo = newRepo("wrong-source");
    String outputRepo = newRepo("wrong-output");
    Project project = connect(sourceRepo, outputRepo, OutputMode.SEPARATE, List.of(OutputKind.SDK));
    GenerationRun run = webhookRun(project);
    String merge = commit(sourceRepo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(sourceRepo, "closed", true, run, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ignored\",\"reason\":\"repo\"}");
    assertThat(vcs.calls("createTag")).isEmpty();
    assertThat(vcs.tags(sourceRepo)).isEmpty();
    assertThat(vcs.tags(outputRepo)).isEmpty();
  }

  @Test
  void pushWithSpecRemovedInSecondCommit_createsRun() throws Exception {
    String repo = newRepo("removed-second");
    Project project = connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK));
    Map<String, Object> payload =
        Map.of(
            "ref", "refs/heads/main",
            "repository", Map.of("full_name", repo),
            "commits",
                List.of(
                    Map.of(
                        "added", List.of(), "modified", List.of("README.md"), "removed", List.of()),
                    Map.of(
                        "added", List.of(), "modified", List.of(), "removed", List.of(SPEC_PATH))));

    Response response = deliver(delivery("push", payload));

    assertThat(response.status()).isEqualTo(200);
    List<GenerationRun> created = runsOf(project);
    assertThat(created).hasSize(1);
    assertThat(response.body())
        .isEqualTo("{\"status\":\"queued\",\"runId\":\"" + created.get(0).getId() + "\"}");
  }

  @Test
  void prOpened_ignored() throws Exception {
    String repo = newRepo("opened");
    Project project = connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK));
    GenerationRun run = webhookRun(project);
    String merge = commit(repo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(repo, "opened", true, run, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"ignored\",\"reason\":\"action\"}");
    assertThat(vcs.tags(repo)).isEmpty();
  }

  @Test
  void tagsMcpWhenSdkTagAlreadyExists() throws Exception {
    String repo = newRepo("partial");
    Project project =
        connect(repo, null, OutputMode.APPEND, List.of(OutputKind.SDK, OutputKind.MCP));
    GenerationRun run = webhookRun(project);
    String earlier = commit(repo, "earlier");
    vcs.createTag(repo, "sdk/v" + VERSION, earlier);
    String merge = commit(repo, "merged");

    Response response =
        deliver(delivery("pull_request", pullRequest(repo, "closed", true, run, merge)));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"status\":\"tagged\"}");
    assertThat(vcs.tags(repo))
        .containsOnly(entry("sdk/v" + VERSION, earlier), entry("mcp/v" + VERSION, merge));
  }

  /** A new repository in the fake VCS, named uniquely so no test sees another's data. */
  private String newRepo(String purpose) {
    String name = purpose + "-" + UUID.randomUUID();
    vcs.createRepo("acme", name, false);
    return "acme/" + name;
  }

  /** A commit on the repository's default branch, which the fake returns as its sha. */
  private String commit(String repo, String message) {
    return vcs.putFile(repo, "file-" + UUID.randomUUID() + ".txt", message, null, message, "main")
        .sha();
  }

  /**
   * Connects a project with a webhook secret, its config and its outputs. {@code outputRepo} is the
   * separate output repository, or null in append mode.
   */
  private Project connect(
      String repo, @Nullable String outputRepo, OutputMode mode, List<OutputKind> outputs) {
    String slug = UUID.randomUUID().toString();
    long githubId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    Organization org =
        organizations.save(new Organization(null, null, slug, slug, slug, false, null));
    User creator =
        users.save(
            new User(
                null,
                githubId,
                "user-" + slug,
                null,
                null,
                "https://avatars.example.com/u/" + githubId));
    Project project =
        projects.save(
            new Project(null, org.getId(), repo, SPEC_PATH, "main", mode, creator.getId()));
    if (outputRepo != null) {
      project.setOutputRepoFullName(outputRepo);
    }
    project.setWebhookSecretEnc(
        cipher.encrypt(
            SECRET, new SecretContext("projects", "webhook_secret_enc", project.getId())));
    project = projects.save(project);
    ProjectConfig config =
        new ProjectConfig(null, project.getId(), 1, VersionStrategy.SPEC_VERSION, creator.getId());
    config.setOutputs(outputs);
    configs.save(config);
    return project;
  }

  /** A run the worker has versioned: a webhook run on the project's config, at {@link #VERSION}. */
  private GenerationRun webhookRun(Project project) {
    ProjectConfig config = configs.findLatest(project.getId()).orElseThrow();
    GenerationRun run =
        new GenerationRun(null, project.getId(), config.getId(), RunTrigger.WEBHOOK, false, null);
    run.setSdkVersion(VERSION);
    return runs.save(run);
  }

  private List<GenerationRun> runsOf(Project project) {
    return runs.findAll().stream()
        .filter(run -> run.getProjectId().equals(project.getId()))
        .toList();
  }

  /** A push payload that adds, modifies or removes nothing but {@code changedPath}. */
  private static Map<String, Object> push(String repo, String ref, String changedPath) {
    return Map.of(
        "ref", ref,
        "repository", Map.of("full_name", repo),
        "commits",
            List.of(
                Map.of(
                    "added", List.of(),
                    "modified", List.of(changedPath),
                    "removed", List.of())));
  }

  /** A pull_request payload for a run's branch, merged or closed as told. */
  private static Map<String, Object> pullRequest(
      String repo, String action, boolean merged, GenerationRun run, String sha) {
    return Map.of(
        "action", action,
        "repository", Map.of("full_name", repo),
        "pull_request",
            Map.of(
                "merged", merged,
                "merge_commit_sha", sha,
                "head", Map.of("ref", "emitkit/run-" + run.getId())));
  }

  private static Delivery delivery(String event, Map<String, Object> payload) {
    return new Delivery(event, JSON.writeValueAsString(payload), SECRET);
  }

  /** Sends a delivery as GitHub would, with no CSRF token and no session. */
  private Response deliver(Delivery delivery) throws Exception {
    byte[] body = delivery.body().getBytes(StandardCharsets.UTF_8);
    MvcResult result =
        mvc.perform(
                post(PATH)
                    .header("X-GitHub-Event", delivery.event())
                    .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                    .header("X-Hub-Signature-256", sign(delivery.signingSecret(), body))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    return new Response(
        result.getResponse().getStatus(), result.getResponse().getContentAsString());
  }

  /** GitHub's X-Hub-Signature-256: {@code sha256=} and the hex HMAC-SHA256 of the raw body. */
  private static String sign(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
  }
}

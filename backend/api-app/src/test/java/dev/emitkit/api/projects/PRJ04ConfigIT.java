package dev.emitkit.api.projects;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.EDITOR;
import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRJ-04: reading and saving a project's generation settings. Runs against Postgres and Redis like
 * PRJ-01, with the secret cipher in its local mode so the stored key can be decrypted in the test.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class PRJ04ConfigIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String CONFIG = "/api/v1/projects/{id}/config";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private SecretCipher cipher;

  private Organization acme;
  private User octo;
  private Project project;

  @BeforeEach
  void resetData() {
    configs.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
    acme =
        organizations.save(new Organization(null, null, "vcs-acme", "acme", "acme", false, null));
    octo = user(1, "octo");
    orgMembers.save(new OrgMember(acme.getId(), octo.getId(), ADMIN, RoleSource.SYNC));
    project =
        projects.save(
            new Project(
                null,
                acme.getId(),
                "acme/api",
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                octo.getId()));
    configs.save(DefaultConfig.version1(project.getId(), octo.getId()));
  }

  @Test
  void put_createsNextVersion() throws Exception {
    putConfig(octo, changes("outputDir", "generated/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.outputDir").value("generated/"))
        .andExpect(jsonPath("$.aiKeySet").value(false));

    List<ProjectConfig> versions = versions();
    assertThat(versions).extracting(ProjectConfig::getVersion).containsExactly(1, 2);
    ProjectConfig first = versions.get(0);
    ProjectConfig second = versions.get(1);
    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(second.getOutputDir()).isEqualTo("generated/");
    assertThat(second.getOutputs()).containsExactly(OutputKind.SDK);
    assertThat(second.getSdkLanguages()).containsExactly("typescript");
    assertThat(second.getCreatedBy()).isEqualTo(octo.getId());
    // The version 1 row is append-only history and keeps its values.
    assertThat(first.getOutputDir()).isEqualTo(".emitkit/");
  }

  @Test
  void get_neverReturnsKey_reportsAiKeySet() throws Exception {
    getConfig(octo).andExpect(status().isOk()).andExpect(jsonPath("$.aiKeySet").value(false));

    putConfig(octo, changes("aiApiKey", "sk-1")).andExpect(status().isOk());

    String json =
        getConfig(octo)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiKeySet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json).doesNotContain("sk-1").doesNotContain("aiApiKey");
  }

  @Test
  void get_viewerCanRead() throws Exception {
    User viewer = user(2, "viewer");
    orgMembers.save(new OrgMember(acme.getId(), viewer.getId(), VIEWER, RoleSource.SYNC));

    getConfig(viewer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.outputDir").value(".emitkit/"));
  }

  @Test
  void get_projectWithoutConfig_404() throws Exception {
    configs.deleteAll();

    getConfig(octo)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void nullKey_keepsPreviousKey_reEncryptedForNewRow() throws Exception {
    putConfig(octo, changes("aiApiKey", "sk-1")).andExpect(status().isOk());
    ProjectConfig before = latest();

    putConfig(octo, changes("aiApiKey", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.aiKeySet").value(true));

    ProjectConfig after = latest();
    assertThat(after.getId()).isNotEqualTo(before.getId());
    assertThat(after.getAiApiKeyEnc()).isNotEqualTo(before.getAiApiKeyEnc());
    assertThat(cipher.decrypt(after.getAiApiKeyEnc(), keyContext(after.getId()))).isEqualTo("sk-1");
    assertThatThrownBy(() -> cipher.decrypt(after.getAiApiKeyEnc(), keyContext(before.getId())))
        .isInstanceOf(AppException.class);
    // The previous row keeps its own ciphertext, still bound to its own id.
    ProjectConfig stillBefore = configs.findById(before.getId()).orElseThrow();
    assertThat(stillBefore.getAiApiKeyEnc()).isEqualTo(before.getAiApiKeyEnc());
    assertThat(cipher.decrypt(stillBefore.getAiApiKeyEnc(), keyContext(before.getId())))
        .isEqualTo("sk-1");
  }

  @Test
  void nullKey_withNoStoredKey_leavesNoKey() throws Exception {
    putConfig(octo, changes("aiApiKey", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aiKeySet").value(false));

    assertThat(latest().getAiApiKeyEnc()).isNull();
  }

  @Test
  void emptyKey_clearsKey() throws Exception {
    putConfig(octo, changes("aiApiKey", "sk-1")).andExpect(status().isOk());

    putConfig(octo, changes("aiApiKey", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aiKeySet").value(false));

    assertThat(latest().getAiApiKeyEnc()).isNull();
  }

  @Test
  void sdkWithoutLanguages_400() throws Exception {
    assertThat(errorsOf(putConfig(octo, changes("sdkLanguages", null))).fields())
        .containsExactly("sdkLanguages");
    assertThat(errorsOf(putConfig(octo, changes("sdkLanguages", List.of()))).fields())
        .containsExactly("sdkLanguages");

    assertThat(versions()).hasSize(1);
  }

  @Test
  void outputDirWithDotDot_400() throws Exception {
    putConfig(octo, changes("outputDir", "generated/../secrets/"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("outputDir"));

    assertThat(versions()).hasSize(1);
  }

  @Test
  void overlongFields_400_atTheColumnLimit_saved() throws Exception {
    String atLimit = "a".repeat(254) + "/";
    putConfig(octo, changes("outputDir", atLimit)).andExpect(status().isOk());

    String overLimit = "a".repeat(255) + "/";
    assertThat(errorsOf(putConfig(octo, changes("outputDir", overLimit))).fields())
        .containsExactly("outputDir");
    assertThat(errorsOf(putConfig(octo, changes("aiProvider", "a".repeat(33)))).fields())
        .containsExactly("aiProvider");

    assertThat(versions()).hasSize(2);
  }

  /** A concurrent save's unique clash is told apart from other integrity failures. */
  @Test
  void versionClash_isRecognised_otherIntegrityErrorsAreNot() {
    Throwable clash =
        catchThrowable(() ->
            configs.saveAndFlush(
                new ProjectConfig(
                    null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, octo.getId())));
    assertThat(clash).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(ConfigService.isVersionClash(clash)).isTrue();

    Throwable missingProject =
        catchThrowable(() ->
            configs.saveAndFlush(
                new ProjectConfig(
                    null,
                    "01MISSINGPROJECT0000000000",
                    1,
                    VersionStrategy.EMITKIT_MANAGED,
                    octo.getId())));
    assertThat(missingProject).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(ConfigService.isVersionClash(missingProject)).isFalse();
  }

  @Test
  void invalidRules_reportEveryViolationAtOnce() throws Exception {
    MvcErrors errors =
        errorsOf(
            putConfig(
                octo,
                changes(
                    "sdkLanguages", List.of("typescript", "rust"),
                    "npmScope", "Bad Scope",
                    "outputDir", "/abs/")));

    assertThat(errors.fields()).containsExactlyInAnyOrder("sdkLanguages", "npmScope", "outputDir");
    assertThat(versions()).hasSize(1);
  }

  @Test
  void languageRules_requireTheirPackageFields() throws Exception {
    MvcErrors errors =
        errorsOf(
            putConfig(
                octo,
                changes(
                    "sdkLanguages",
                    List.of("python", "java"),
                    "pypiName",
                    null,
                    "mavenGroupId",
                    null,
                    "mavenArtifactId",
                    " ")));
    assertThat(errors.fields())
        .containsExactlyInAnyOrder("pypiName", "mavenGroupId", "mavenArtifactId");

    putConfig(
            octo,
            changes(
                "sdkLanguages", List.of("python", "java"),
                "pypiName", "emitkit-pets",
                "mavenGroupId", "dev.emitkit",
                "mavenArtifactId", "pets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sdkLanguages.length()").value(2));
  }

  @Test
  void editorPut_403() throws Exception {
    User editor = user(2, "editor");
    orgMembers.save(new OrgMember(acme.getId(), editor.getId(), EDITOR, RoleSource.SYNC));

    putConfig(editor, changes())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(versions()).hasSize(1);
  }

  @Test
  void concurrentPuts_neverDuplicateVersion() throws Exception {
    int writers = 4;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch start = new CountDownLatch(1);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        Callable<Integer> writer =
            () -> {
              start.await();
              return putConfig(octo, changes()).andReturn().getResponse().getStatus();
            };
        results.add(pool.submit(writer));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }
    } finally {
      pool.shutdown();
    }

    assertThat(statuses).allMatch(s -> s == 200 || s == 409);
    long saved = statuses.stream().filter(s -> s == 200).count();
    assertThat(saved).isPositive();
    List<ProjectConfig> versions = versions();
    assertThat(versions).hasSize((int) saved + 1);
    assertThat(versions)
        .extracting(ProjectConfig::getVersion)
        .containsExactlyElementsOf(IntStream.rangeClosed(1, versions.size()).boxed().toList());
  }

  /** The field names of a validation response's errors. */
  private record MvcErrors(List<String> fields) {}

  private MvcErrors errorsOf(ResultActions result) throws Exception {
    String body =
        result
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> fields = JsonPath.read(body, "$.errors[*].field");
    return new MvcErrors(fields);
  }

  private ResultActions getConfig(User user) throws Exception {
    return mvc.perform(get(CONFIG, project.getId()).with(signedInAs(user)));
  }

  private ResultActions putConfig(User user, Map<String, Object> changes) throws Exception {
    return mvc.perform(
        put(CONFIG, project.getId())
            .with(csrf())
            .with(signedInAs(user))
            .contentType("application/json")
            .content(body(changes)));
  }

  /** The valid config body with {@code changes} applied. A null value is sent as JSON null. */
  private static String body(Map<String, Object> changes) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("outputs", List.of("SDK"));
    body.put("sdkLanguages", List.of("typescript"));
    body.put("outputDir", ".emitkit/");
    body.put("versionStrategy", "EMITKIT_MANAGED");
    body.put("breakingGate", "ALLOW");
    body.put("lintFailOn", "NEVER");
    body.putAll(changes);
    return JSON.writeValueAsString(body);
  }

  /** Pairs of field name and value, in order; the values may be null. */
  private static Map<String, Object> changes(Object... fieldsAndValues) {
    Map<String, Object> changes = new LinkedHashMap<>();
    for (int i = 0; i < fieldsAndValues.length; i += 2) {
      changes.put((String) fieldsAndValues[i], fieldsAndValues[i + 1]);
    }
    return changes;
  }

  private List<ProjectConfig> versions() {
    return configs.findAll().stream()
        .filter(c -> c.getProjectId().equals(project.getId()))
        .sorted(Comparator.comparingInt(ProjectConfig::getVersion))
        .toList();
  }

  private ProjectConfig latest() {
    return configs.findLatest(project.getId()).orElseThrow();
  }

  private static SecretContext keyContext(String configId) {
    return new SecretContext("project_configs", "ai_api_key_enc", configId);
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }

  private User user(long githubId, String login) {
    return users.save(
        new User(null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
  }
}

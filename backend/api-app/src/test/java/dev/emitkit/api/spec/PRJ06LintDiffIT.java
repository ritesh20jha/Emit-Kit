package dev.emitkit.api.spec;

import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.api.projects.LintRequest;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.spec.diff.SpecDiffer;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.model.ApiModelJson;
import dev.emitkit.spec.parse.SpecParser;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lint endpoint, the snapshot list and the spec diff, end to end against real PostgreSQL and
 * Redis. Snapshots are inserted with explicit {@code created_at} values, so their order is fixed.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class PRJ06LintDiffIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SpecParser PARSER = new SpecParser();
  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  private static final EmitKitUser LINTER = new EmitKitUser("01LINTERUSER00000000000000", "linter");

  /** Line 7 is the http server; line 10 is the operation, which has no operationId. */
  private static final String LINE_SPEC =
      """
      openapi: 3.1.0
      info:
        title: Pets
        version: 1.0.0
        description: Pet store
      servers:
        - url: http://api.example.com
      paths:
        /pets:
          get:
            description: List pets
            responses:
              '200':
                description: ok
      """;

  @Autowired private MockMvc mvc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private ProjectRepository projects;
  @Autowired private SpecSnapshotRepository snapshots;
  @Autowired private OAuthAccountRepository oauth;

  @BeforeEach
  void resetData() {
    snapshots.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
  }

  @Test
  void lint_invalidYaml_returnsSpecParseIssue() throws Exception {
    lint("openapi: [unclosed", LINTER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ruleId").value("spec-parse"))
        .andExpect(jsonPath("$[0].severity").value("ERROR"));
  }

  @Test
  void lint_aliasBomb_returnsParseIssue() throws Exception {
    // Each level holds two aliases of the level below; 20 levels would expand to over a million
    // nodes.
    var yaml = new StringBuilder("openapi: 3.0.3\ninfo: {title: T, version: \"1\"}\npaths: {}\n");
    yaml.append("x-l0: &l0 {v: 1}\n");
    for (int i = 1; i <= 20; i++) {
      yaml.append("x-l%d: &l%d {a: *l%d, b: *l%d}\n".formatted(i, i, i - 1, i - 1));
    }
    lint(yaml.toString(), LINTER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ruleId").value("spec-parse"))
        .andExpect(jsonPath("$[0].severity").value("ERROR"));
  }

  @Test
  void lint_returnsLineNumbers() throws Exception {
    lint(LINE_SPEC, LINTER)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.ruleId=='server-url-https')].line").value(7))
        .andExpect(jsonPath("$[?(@.ruleId=='operation-operationId')].line").value(10))
        .andExpect(jsonPath("$[?(@.line == null)]").isEmpty());
  }

  @Test
  void lint_rateLimited_429() throws Exception {
    // The limit is 30 a minute: seed this minute's counter at 30 and the next request is refused.
    EmitKitUser busy = new EmitKitUser("01BUSYUSER000000000000000", "busy");
    long minute = Instant.now().getEpochSecond() / 60;
    redis.opsForValue().set("ratelimit:lint:" + busy.userId() + ":" + minute, "30");

    lint(LINE_SPEC, busy)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void lint_redisDown_failsOpen() throws IOException {
    // A port nothing listens on, so the connection is refused at once instead of timing out.
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    LettuceConnectionFactory down = new LettuceConnectionFactory("127.0.0.1", closedPort);
    down.afterPropertiesSet();
    try {
      StringRedisTemplate redisDown = new StringRedisTemplate(down);
      assertThatThrownBy(() -> redisDown.opsForValue().get("probe"))
          .isInstanceOf(DataAccessException.class);

      var controller = new LintController(new LintRateLimiter(redisDown));
      List<LintIssue> issues = controller.lint(LINTER, new LintRequest(LINE_SPEC));

      assertThat(issues)
          .filteredOn(issue -> issue.ruleId().equals("server-url-https"))
          .singleElement()
          .satisfies(issue -> assertThat(issue.line()).isEqualTo(7));
    } finally {
      down.destroy();
    }
  }

  @Test
  void snapshots_newestFirstAndCappedAt100() throws Exception {
    User viewer = user(1, "viewer");
    Project widgets = project(member(org("acme"), viewer), viewer, "acme/widgets");
    String model = ApiModelJson.toJson(PARSER.parse(LINE_SPEC).model());
    for (long n = 1; n <= 101; n++) {
      insertRow(widgets, n, "v" + n, model);
    }

    signedGet("/api/v1/projects/" + widgets.getId() + "/spec/snapshots", viewer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(100))
        .andExpect(jsonPath("$[0].infoVersion").value("v101"))
        .andExpect(jsonPath("$[99].infoVersion").value("v2"))
        .andExpect(jsonPath("$[?(@.infoVersion=='v1')]").isEmpty());
  }

  @Test
  void snapshots_nonMember_404() throws Exception {
    User owner = user(1, "owner");
    Project widgets = project(member(org("acme"), owner), owner, "acme/widgets");
    insertSnapshot(widgets, 1, "1.0.0", LINE_SPEC);
    User stranger = user(2, "stranger");

    signedGet("/api/v1/projects/" + widgets.getId() + "/spec/snapshots", stranger)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void diff_matchesDiffer() throws Exception {
    String v1 = spec("1.0.0", "listPets");
    String v2 = spec("2.0.0", "listAllPets");
    User viewer = user(1, "viewer");
    Project widgets = project(member(org("acme"), viewer), viewer, "acme/widgets");
    insertSnapshot(widgets, 1, "1.0.0", v1);
    insertSnapshot(widgets, 2, "2.0.0", v2);

    var expected = new SpecDiffer().diff(PARSER.parse(v1).model(), PARSER.parse(v2).model());

    signedGet(diffPath(widgets, 1, 2), viewer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.added[0].operationId").value("listAllPets"))
        .andExpect(jsonPath("$.removed[0].operationId").value("listPets"))
        .andExpect(content().json(JSON.writeValueAsString(expected), true));
  }

  @Test
  void diff_snapshotOfOtherProject_404() throws Exception {
    User owner = user(1, "owner");
    Organization acme = org("acme");
    member(acme, owner);
    Project widgets = project(acme, owner, "acme/widgets");
    Project gadgets = project(acme, owner, "acme/gadgets");
    insertSnapshot(widgets, 1, "1.0.0", spec("1.0.0", "listPets"));
    insertSnapshot(gadgets, 2, "1.0.0", spec("1.0.0", "listGadgets"));

    signedGet(diffPath(widgets, 1, 2), owner)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    signedGet(diffPath(widgets, 2, 1), owner)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  private ResultActions lint(String content, EmitKitUser user) throws Exception {
    return mvc.perform(
        post("/api/v1/lint")
            .with(csrf())
            .with(signedInAs(user))
            .contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("content", content))));
  }

  private ResultActions signedGet(String path, User user) throws Exception {
    return mvc.perform(get(path).with(signedInAs(new EmitKitUser(user.getId(), user.getLogin()))));
  }

  private static RequestPostProcessor signedInAs(EmitKitUser user) {
    return authentication(new OAuth2AuthenticationToken(user, List.of(), "github"));
  }

  private User user(long githubId, String login) {
    return users.save(
        new User(null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
  }

  private Organization org(String slug) {
    return organizations.save(new Organization(null, null, "vcs-" + slug, slug, slug, false, null));
  }

  /** Makes {@code user} a viewer of {@code org}, which holds SPEC_VIEW, and returns the org. */
  private Organization member(Organization org, User user) {
    orgMembers.save(new OrgMember(org.getId(), user.getId(), VIEWER, RoleSource.SYNC));
    return org;
  }

  private Project project(Organization org, User creator, String repo) {
    return projects.save(
        new Project(
            null, org.getId(), repo, "openapi.yaml", "main", OutputMode.APPEND, creator.getId()));
  }

  private static String diffPath(Project project, long from, long to) {
    return "/api/v1/projects/%s/spec/diff?from=%s&to=%s"
        .formatted(project.getId(), snapshotId(from), snapshotId(to));
  }

  private static String snapshotId(long n) {
    return String.format("01SNAP%020d", n);
  }

  private void insertSnapshot(Project project, long n, String infoVersion, String spec) {
    insertRow(project, n, infoVersion, ApiModelJson.toJson(PARSER.parse(spec).model()));
  }

  /** Inserts a snapshot created {@code n} minutes after {@link #BASE}, so later n is newer. */
  private void insertRow(Project project, long n, String infoVersion, String apiModel) {
    jdbc.update(
        "insert into spec_snapshots (id, project_id, content_hash, raw_content, api_model,"
            + " info_version, created_at) values (?, ?, ?, ?, cast(? as jsonb), ?, ?)",
        snapshotId(n),
        project.getId(),
        String.format("%064d", n),
        "raw",
        apiModel,
        infoVersion,
        Timestamp.from(BASE.plusSeconds(n * 60L)));
  }

  private static String spec(String version, String operationId) {
    return """
    openapi: 3.1.0
    info:
      title: Pets
      version: %s
      description: Pet store
    paths:
      /pets:
        get:
          operationId: %s
          description: List pets
          responses:
            '200':
              description: ok
    """
        .formatted(version, operationId);
  }
}

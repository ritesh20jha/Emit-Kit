package dev.emitkit.vcs.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.contract.VcsClientContractTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;

/**
 * Runs the shared contract with WireMock, one stateful scenario per ref or pull request it creates.
 */
@WireMockTest
class GitHubContractTest extends VcsClientContractTest {

  private static final String HEAD = "head-sha";
  private static final String BASE_TREE = "base-tree-sha";
  private static final String NEW_TREE = "new-tree-sha";
  private static final String COMMIT = "commit-sha";
  private static final String PR_JSON =
      "{\"number\":7,\"html_url\":\"https://github.com/acme/api/pull/7\","
          + "\"head\":{\"ref\":\"feat\"},\"base\":{\"ref\":\"main\"}}";

  private VcsClient client;

  @BeforeEach
  void setUp(WireMockRuntimeInfo wm) {
    String sha = GitBlob.sha(OPENAPI_CONTENT);
    stubFor(
        get(urlPathEqualTo("/repos/acme/api"))
            .willReturn(
                okJson(
                    "{\"name\":\"api\",\"full_name\":\"acme/api\",\"owner\":{\"login\":\"acme\"}}")));
    stubFor(
        get(urlPathEqualTo("/repos/acme/nope"))
            .willReturn(json(404, "{\"message\":\"Not Found\"}")));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(
                okJson(
                    """
                    {"type":"file","encoding":"base64","size":14,"name":"openapi.yaml",
                     "path":"openapi.yaml","sha":"%s","content":"b3BlbmFwaTogMy4xLjA=\\n"}\
                    """
                        .formatted(sha))));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/contents/nope.yaml"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));
    // The first write succeeds and moves the file on; a second write with the old sha conflicts.
    stubFor(
        put(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .inScenario("stale-sha")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("written")
            .willReturn(
                okJson(
                    "{\"content\":{\"sha\":\"newsha\"},\"commit\":{\"sha\":\"c0ffee\","
                        + "\"html_url\":\"https://github.com/acme/api/commit/c0ffee\"}}")));
    stubFor(
        put(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .inScenario("stale-sha")
            .whenScenarioStateIs("written")
            .willReturn(aResponse().withStatus(409)));

    // Branches main, b0, b1 and b2 all point at HEAD; the others are missing.
    for (String branch : List.of("main", "b0", "b1", "b2")) {
      stubFor(
          get(urlPathEqualTo("/repos/acme/api/git/refs/heads/" + branch))
              .willReturn(json(200, refJson("refs/heads/" + branch, HEAD))));
    }
    for (String missing : List.of("nope", "gone")) {
      stubFor(
          get(urlPathEqualTo("/repos/acme/api/git/refs/heads/" + missing))
              .willReturn(json(404, "{\"message\":\"Not Found\"}")));
    }
    stubFor(
        patch(urlPathEqualTo("/repos/acme/api/git/refs/heads/b1"))
            .willReturn(json(200, refJson("refs/heads/b1", COMMIT))));
    stubFor(
        get(urlEqualTo("/repos/acme/api/git/trees/" + HEAD))
            .willReturn(json(200, "{\"sha\":\"" + BASE_TREE + "\",\"tree\":[]}")));
    stubFor(
        post(urlPathEqualTo("/repos/acme/api/git/trees"))
            .willReturn(json(201, "{\"sha\":\"" + NEW_TREE + "\",\"tree\":[]}")));
    stubFor(
        post(urlPathEqualTo("/repos/acme/api/git/commits"))
            .willReturn(
                json(
                    201,
                    "{\"sha\":\""
                        + COMMIT
                        + "\",\"html_url\":\"https://github.com/acme/api/commit/"
                        + COMMIT
                        + "\"}")));
    stubFor(
        get(urlEqualTo("/repos/acme/api/git/trees/b1?recursive=1"))
            .willReturn(
                json(
                    200,
                    "{\"sha\":\""
                        + NEW_TREE
                        + "\",\"truncated\":false,\"tree\":[{\"path\":"
                        + "\"out/a.ts\",\"type\":\"blob\",\"sha\":\""
                        + GitBlob.sha("x")
                        + "\"}]}")));

    for (String ref :
        List.of(
            "refs/heads/b0",
            "refs/heads/b1",
            "refs/heads/b2",
            "refs/heads/feat",
            "refs/tags/v1.0.0")) {
      refCreates(ref);
    }
    // The first pull request opens; a second for the same head and base reports it exists.
    stubFor(
        post(urlPathEqualTo("/repos/acme/api/pulls"))
            .inScenario("open pr")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("open")
            .willReturn(json(201, PR_JSON)));
    stubFor(
        post(urlPathEqualTo("/repos/acme/api/pulls"))
            .inScenario("open pr")
            .whenScenarioStateIs("open")
            .willReturn(
                json(
                    422,
                    "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"A pull request"
                        + " already exists for acme:feat.\"}]}")));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api/pulls"))
            .withQueryParam("head", equalTo("acme:feat"))
            .withQueryParam("base", equalTo("main"))
            .willReturn(okJson("[" + PR_JSON + "]")));

    client =
        new GitHubVcsProvider(new GitHubProperties(wm.getHttpBaseUrl(), null, 256), t -> "t", null)
            .forUser("t");
  }

  @Override
  protected VcsClient client() {
    return client;
  }

  /** Creates {@code ref}: the first create succeeds, and every later create reports it exists. */
  private static void refCreates(String ref) {
    String scenario = "create " + ref;
    stubFor(
        createRef(ref)
            .inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("exists")
            .willReturn(json(201, refJson(ref, HEAD))));
    stubFor(
        createRef(ref)
            .inScenario(scenario)
            .whenScenarioStateIs("exists")
            .willReturn(json(422, "{\"message\":\"Reference already exists\"}")));
  }

  private static MappingBuilder createRef(String ref) {
    return post(urlPathEqualTo("/repos/acme/api/git/refs"))
        .withRequestBody(matchingJsonPath("$.ref", equalTo(ref)));
  }

  private static ResponseDefinitionBuilder json(int status, String body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  /** A ref as GitHub returns it. The url is the ref's API path, which a later update is sent to. */
  private static String refJson(String ref, String sha) {
    return "{\"ref\":\""
        + ref
        + "\",\"url\":\"/repos/acme/api/git/"
        + ref
        + "\",\"object\":{\"sha\":\""
        + sha
        + "\",\"type\":\"commit\"}}";
  }
}

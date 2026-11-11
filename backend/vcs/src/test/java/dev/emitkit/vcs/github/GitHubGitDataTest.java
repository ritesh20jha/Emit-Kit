package dev.emitkit.vcs.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.CommitRef;
import dev.emitkit.vcs.PullRequest;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the git data calls: refs, single-commit tree writes, blob-sha listing, pull requests and
 * comments. The shared contract checks behaviour; these tests check what GitHub receives.
 */
@WireMockTest
class GitHubGitDataTest {

  private static final String REPO = "/repos/acme/api";
  private static final String HEAD = "head-sha";
  private static final String BASE_TREE = "base-tree-sha";
  private static final String COMMIT = "commit-sha";
  private static final String TREES = REPO + "/git/trees";
  private static final String COMMITS = REPO + "/git/commits";
  private static final String PULLS = REPO + "/pulls";
  private static final String REFS = REPO + "/git/refs";
  private static final String EXISTS = "{\"message\":\"Reference already exists\"}";
  private static final String PR_EXISTS =
      "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"A pull request already"
          + " exists for acme:feat.\"}]}";

  private VcsClient client;

  @BeforeEach
  void setUp(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlPathEqualTo(REPO))
            .willReturn(
                okJson(
                    "{\"name\":\"api\",\"full_name\":\"acme/api\",\"owner\":{\"login\":\"acme\"}}")));
    stubBranchAt("emitkit/run-1", HEAD);
    stubFor(
        get(urlPathEqualTo(REPO + "/commits/" + HEAD))
            .willReturn(
                json(
                    200,
                    "{\"sha\":\""
                        + HEAD
                        + "\",\"commit\":{\"tree\":{\"sha\":\""
                        + BASE_TREE
                        + "\"}}}")));
    stubFor(post(urlPathEqualTo(TREES)).willReturn(json(201, treeJson("tree-sha"))));
    stubFor(
        post(urlPathEqualTo(COMMITS))
            .willReturn(
                json(
                    201,
                    "{\"sha\":\""
                        + COMMIT
                        + "\",\"html_url\":\"https://github.com/acme/api/commit/"
                        + COMMIT
                        + "\"}")));
    stubFor(
        patch(urlPathMatching(REFS + "/heads/.*"))
            .willReturn(
                json(200, "{\"ref\":\"refs/heads/x\",\"object\":{\"sha\":\"" + COMMIT + "\"}}")));
    client =
        new GitHubVcsProvider(new GitHubProperties(wm.getHttpBaseUrl(), null, 256), t -> "t", null)
            .forUser("t");
  }

  @Test
  void commitTree_usesFourCallsFor50Files() {
    client.commitTree("acme/api", "emitkit/run-1", files(50), "msg");
    verify(1, postRequestedFor(urlPathEqualTo(TREES)));
    verify(1, postRequestedFor(urlPathEqualTo(COMMITS)));
    verify(1, patchRequestedFor(urlPathEqualTo(REFS + "/heads/emitkit/run-1")));
    verify(0, postRequestedFor(urlPathEqualTo(REPO + "/git/blobs")));
  }

  @Test
  void commitTree_buildsOnParentTreeAndMovesBranchToTheCommit() {
    CommitRef commit =
        client.commitTree(
            "acme/api", "emitkit/run-1", List.of(new CommitFile("out/a.ts", "x")), "msg");

    assertThat(commit)
        .isEqualTo(new CommitRef(COMMIT, "https://github.com/acme/api/commit/" + COMMIT));
    verify(
        postRequestedFor(urlPathEqualTo(TREES))
            .withRequestBody(matchingJsonPath("$.base_tree", equalTo(BASE_TREE)))
            .withRequestBody(matchingJsonPath("$.tree[0].path", equalTo("out/a.ts")))
            .withRequestBody(matchingJsonPath("$.tree[0].content", equalTo("x"))));
    verify(
        postRequestedFor(urlPathEqualTo(COMMITS))
            .withRequestBody(matchingJsonPath("$.parents[0]", equalTo(HEAD)))
            .withRequestBody(matchingJsonPath("$.tree", equalTo("tree-sha")))
            .withRequestBody(matchingJsonPath("$.message", equalTo("msg"))));
    verify(
        patchRequestedFor(urlPathEqualTo(REFS + "/heads/emitkit/run-1"))
            .withRequestBody(matchingJsonPath("$.sha", equalTo(COMMIT))));
  }

  @Test
  void commitTree_over1000Files_chainsTreesAndMakesOneCommit() {
    // Each tree POST returns the next tree sha, so each chunk's base_tree names the one before it.
    stubFor(
        post(urlPathEqualTo(TREES))
            .inScenario("trees")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("one")
            .willReturn(json(201, treeJson("tree-1"))));
    stubFor(
        post(urlPathEqualTo(TREES))
            .inScenario("trees")
            .whenScenarioStateIs("one")
            .willSetStateTo("two")
            .willReturn(json(201, treeJson("tree-2"))));
    stubFor(
        post(urlPathEqualTo(TREES))
            .inScenario("trees")
            .whenScenarioStateIs("two")
            .willReturn(json(201, treeJson("tree-3"))));

    client.commitTree("acme/api", "emitkit/run-1", files(2500), "msg");

    verify(3, postRequestedFor(urlPathEqualTo(TREES)));
    verify(
        1,
        postRequestedFor(urlPathEqualTo(TREES))
            .withRequestBody(matchingJsonPath("$.base_tree", equalTo(BASE_TREE)))
            .withRequestBody(matchingJsonPath("$.tree.length()", equalTo("1000"))));
    verify(
        1,
        postRequestedFor(urlPathEqualTo(TREES))
            .withRequestBody(matchingJsonPath("$.base_tree", equalTo("tree-1")))
            .withRequestBody(matchingJsonPath("$.tree.length()", equalTo("1000"))));
    verify(
        1,
        postRequestedFor(urlPathEqualTo(TREES))
            .withRequestBody(matchingJsonPath("$.base_tree", equalTo("tree-2")))
            .withRequestBody(matchingJsonPath("$.tree.length()", equalTo("500"))));
    verify(
        1,
        postRequestedFor(urlPathEqualTo(COMMITS))
            .withRequestBody(matchingJsonPath("$.tree", equalTo("tree-3"))));
    verify(1, postRequestedFor(urlPathEqualTo(COMMITS)));
    verify(1, patchRequestedFor(urlPathEqualTo(REFS + "/heads/emitkit/run-1")));
  }

  @Test
  void listBlobShas_filtersByPrefix() {
    stubFor(
        get(urlEqualTo(TREES + "/main?recursive=1"))
            .willReturn(
                json(
                    200,
                    """
                    {"sha":"root","truncated":false,"tree":[
                      {"path":"out","type":"tree","sha":"out-tree"},
                      {"path":"out/a.ts","type":"blob","sha":"sha-a"},
                      {"path":"out/b.ts","type":"blob","sha":"sha-b"},
                      {"path":"src/c.ts","type":"blob","sha":"sha-c"}]}\
                    """)));

    assertThat(client.listBlobShas("acme/api", "main", "out/"))
        .isEqualTo(Map.of("out/a.ts", "sha-a", "out/b.ts", "sha-b"));
  }

  @Test
  void listBlobShas_truncatedTree_walksSubtrees() {
    stubFor(
        get(urlEqualTo(TREES + "/main?recursive=1"))
            .willReturn(json(200, "{\"sha\":\"root\",\"truncated\":true,\"tree\":[]}")));
    stubFor(
        get(urlEqualTo(TREES + "/main"))
            .willReturn(
                json(
                    200,
                    """
                    {"sha":"root","tree":[
                      {"path":"out","type":"tree","sha":"out-tree"},
                      {"path":"src","type":"tree","sha":"src-tree"},
                      {"path":"README.md","type":"blob","sha":"sha-readme"}]}\
                    """)));
    stubFor(
        get(urlEqualTo(TREES + "/out-tree"))
            .willReturn(
                json(
                    200,
                    """
                    {"sha":"out-tree","tree":[
                      {"path":"a.ts","type":"blob","sha":"sha-a"},
                      {"path":"sub","type":"tree","sha":"sub-tree"}]}\
                    """)));
    stubFor(
        get(urlEqualTo(TREES + "/sub-tree"))
            .willReturn(
                json(
                    200,
                    "{\"sha\":\"sub-tree\",\"tree\":[{\"path\":\"deep.ts\","
                        + "\"type\":\"blob\",\"sha\":\"sha-deep\"}]}")));

    assertThat(client.listBlobShas("acme/api", "main", "out/"))
        .isEqualTo(Map.of("out/a.ts", "sha-a", "out/sub/deep.ts", "sha-deep"));
    verify(0, getRequestedFor(urlEqualTo(TREES + "/src-tree")));
  }

  @Test
  void openPr_existingOpenPr_returnsIt() {
    stubFor(
        post(urlPathEqualTo(PULLS))
            .inScenario("pr")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("open")
            .willReturn(json(201, pr(7, "main"))));
    stubFor(
        post(urlPathEqualTo(PULLS))
            .inScenario("pr")
            .whenScenarioStateIs("open")
            .willReturn(json(422, PR_EXISTS)));
    stubFor(
        get(urlPathEqualTo(PULLS))
            .withQueryParam("state", equalTo("open"))
            .withQueryParam("head", equalTo("acme:feat"))
            .withQueryParam("base", equalTo("main"))
            .willReturn(okJson("[" + pr(7, "main") + "]")));

    PullRequest opened = client.openPr("acme/api", "feat", "main", "t", "b");

    assertThat(client.openPr("acme/api", "feat", "main", "other", "other")).isEqualTo(opened);
    assertThat(opened).isEqualTo(new PullRequest(7, prUrl(7), "feat"));
  }

  @Test
  void openPr_existingPrForAnotherBase_isNotReturned() {
    stubFor(post(urlPathEqualTo(PULLS)).willReturn(json(422, PR_EXISTS)));
    stubFor(
        get(urlPathEqualTo(PULLS))
            .withQueryParam("head", equalTo("acme:feat"))
            .withQueryParam("base", equalTo("main"))
            .willReturn(okJson("[" + pr(3, "develop") + "," + pr(7, "main") + "]")));

    assertThat(client.openPr("acme/api", "feat", "main", "t", "b"))
        .isEqualTo(new PullRequest(7, prUrl(7), "feat"));
  }

  @Test
  void createTag_existing_returnsFalse() {
    stubFor(
        post(urlPathEqualTo(REFS))
            .withRequestBody(matchingJsonPath("$.ref", equalTo("refs/tags/v1.0.0")))
            .willReturn(json(422, EXISTS)));

    assertThat(client.createTag("acme/api", "v1.0.0", HEAD)).isFalse();
    verify(
        postRequestedFor(urlPathEqualTo(REFS))
            .withRequestBody(matchingJsonPath("$.sha", equalTo(HEAD))));
  }

  @Test
  void deleteBranch_missing_isNoOp() {
    stubFor(
        get(urlPathEqualTo(REFS + "/heads/gone"))
            .willReturn(json(404, "{\"message\":\"Not Found\"}")));

    assertThatCode(() -> client.deleteBranch("acme/api", "gone")).doesNotThrowAnyException();
    verify(0, deleteRequestedFor(urlPathEqualTo(REFS + "/heads/gone")));
  }

  @Test
  void deleteBranch_existing_deletesTheRef() {
    stubBranchAt("old", HEAD);
    stubFor(delete(urlPathEqualTo(REFS + "/heads/old")).willReturn(aResponse().withStatus(204)));

    client.deleteBranch("acme/api", "old");

    verify(1, deleteRequestedFor(urlPathEqualTo(REFS + "/heads/old")));
  }

  @Test
  void createBranch_existingAtSameSha_isNoOp() {
    stubFor(
        post(urlPathEqualTo(REFS))
            .withRequestBody(matchingJsonPath("$.ref", equalTo("refs/heads/b1")))
            .willReturn(json(422, EXISTS)));
    stubBranchAt("b1", HEAD);

    assertThatCode(() -> client.createBranch("acme/api", "b1", HEAD)).doesNotThrowAnyException();
    verify(1, getRequestedFor(urlPathEqualTo(REFS + "/heads/b1")));
  }

  @Test
  void comment_postsOnThePullRequestThread() {
    stubFor(
        get(urlPathEqualTo(REPO + "/issues/7"))
            .willReturn(json(200, "{\"number\":7,\"html_url\":\"" + prUrl(7) + "\"}")));
    stubFor(
        post(urlPathEqualTo(REPO + "/issues/7/comments"))
            .willReturn(json(201, "{\"id\":1,\"body\":\"hi\"}")));

    client.comment("acme/api", 7, "hi");

    verify(
        postRequestedFor(urlPathEqualTo(REPO + "/issues/7/comments"))
            .withRequestBody(matchingJsonPath("$.body", equalTo("hi"))));
  }

  @Test
  void comment_missingPr_throwsNotFound() {
    stubFor(
        get(urlPathEqualTo(REPO + "/issues/99"))
            .willReturn(json(404, "{\"message\":\"Not Found\"}")));

    assertThatThrownBy(() -> client.comment("acme/api", 99, "hi"))
        .isInstanceOf(VcsNotFoundException.class);
  }

  /**
   * Stubs a branch read: GET of {@code name} returns a ref at {@code sha}. The url is what a delete
   * of the ref is sent to, so it is the ref's API path.
   */
  private static void stubBranchAt(String name, String sha) {
    stubFor(
        get(urlPathEqualTo(REFS + "/heads/" + name))
            .willReturn(
                json(
                    200,
                    "{\"ref\":\"refs/heads/"
                        + name
                        + "\",\"url\":\""
                        + REFS
                        + "/heads/"
                        + name
                        + "\",\"object\":{\"sha\":\""
                        + sha
                        + "\",\"type\":\"commit\"}}")));
  }

  private static List<CommitFile> files(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new CommitFile("out/f" + i + ".ts", "x" + i))
        .toList();
  }

  private static String prUrl(int number) {
    return "https://github.com/acme/api/pull/" + number;
  }

  private static String pr(int number, String base) {
    return "{\"number\":"
        + number
        + ",\"html_url\":\""
        + prUrl(number)
        + "\","
        + "\"head\":{\"ref\":\"feat\"},\"base\":{\"ref\":\""
        + base
        + "\"}}";
  }

  /** A tree write's response: the new tree's sha, with its (here empty) entry list. */
  private static String treeJson(String sha) {
    return "{\"sha\":\"" + sha + "\",\"tree\":[]}";
  }

  private static ResponseDefinitionBuilder json(int status, String body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}

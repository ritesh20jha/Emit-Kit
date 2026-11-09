package dev.emitkit.vcs.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.contract.VcsClientContractTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Runs the shared contract against the GitHub client with WireMock. Only the file contract tests
 * run here. The branch, tag, commit and pull-request tests belong to VCS-03 and are disabled.
 */
@WireMockTest
class GitHubContractTest extends VcsClientContractTest {

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
        get(urlPathEqualTo("/repos/acme/api/contents/openapi.yaml"))
            .willReturn(
                okJson(
                    "{\"type\":\"file\",\"encoding\":\"base64\",\"size\":14,\"name\":\"openapi.yaml\","
                        + "\"path\":\"openapi.yaml\",\"sha\":\""
                        + sha
                        + "\",\"content\":\"b3BlbmFwaTogMy4xLjA=\\n\"}")));
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
            .willReturn(aResponse().withStatus(409).withBody("{\"message\":\"does not match\"}")));
    client =
        new GitHubVcsProvider(new GitHubProperties(wm.getHttpBaseUrl(), null, 256), t -> "t", null)
            .forUser("t");
  }

  @Override
  protected VcsClient client() {
    return client;
  }

  @Override
  @Disabled("VCS-03")
  @Test
  protected void commitTree_thenListBlobShas() {}

  @Override
  @Disabled("VCS-03")
  @Test
  protected void createTag_twice_secondReturnsFalse() {}

  @Override
  @Disabled("VCS-03")
  @Test
  protected void branchHead_returnsTheBranchCommit() {}

  @Override
  @Disabled("VCS-03")
  @Test
  protected void branchHead_missingRepoOrBranch_throwsNotFound() {}

  @Override
  @Disabled("VCS-03")
  @Test
  protected void createBranch_atSameSha_isNoOp() {}

  @Override
  @Disabled("VCS-03")
  @Test
  protected void createBranch_atDifferentSha_throwsConflict() {}

  @Override
  @Disabled("VCS-03")
  @Test
  protected void deleteBranch_missing_isNoOp() {}

  @Override
  @Disabled("VCS-03")
  @Test
  protected void openPr_sameHead_returnsTheExistingPr() {}
}

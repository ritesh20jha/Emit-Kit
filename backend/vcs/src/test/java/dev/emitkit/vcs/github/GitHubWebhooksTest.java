package dev.emitkit.vcs.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.Webhook;
import dev.emitkit.vcs.error.VcsServerException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WireMockTest
class GitHubWebhooksTest {

  private static final String CALLBACK = "https://emitkit.example/hooks/vcs";

  private VcsClient client;

  @BeforeEach
  void setUp(WireMockRuntimeInfo wm) {
    // The client looks up the identity and the repository first.
    stubFor(get(urlPathEqualTo("/user")).willReturn(okJson(body("user.json"))));
    stubFor(
        get(urlPathEqualTo("/repos/acme/api"))
            .willReturn(
                okJson(
                    "{\"name\":\"api\",\"full_name\":\"acme/api\",\"owner\":{\"login\":\"acme\"}}")));
    client = provider(wm).forUser("token");
  }

  @Test
  void register_sendsEventsAndSecret() {
    stubFor(
        post(urlPathEqualTo("/repos/acme/api/hooks"))
            .willReturn(
                okJson(
                    "{\"id\":42,\"name\":\"web\",\"active\":true,"
                        + "\"events\":[\"push\",\"pull_request\"],"
                        + "\"config\":{\"url\":\""
                        + CALLBACK
                        + "\",\"content_type\":\"json\"}}")));

    assertThat(
            client.registerWebhook("acme/api", CALLBACK, "s3cret", Set.of("push", "pull_request")))
        .isEqualTo(new Webhook(42L, CALLBACK));

    verify(
        postRequestedFor(urlPathEqualTo("/repos/acme/api/hooks"))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "name": "web",
                      "active": true,
                      "events": ["push", "pull_request"],
                      "config": {
                        "url": "%s",
                        "content_type": "json",
                        "secret": "s3cret",
                        "insecure_ssl": "0"
                      }
                    }
                    """
                        .formatted(CALLBACK),
                    true,
                    false)));
  }

  @Test
  void delete_sendsDeleteToHookPath() {
    stubFor(
        delete(urlPathEqualTo("/repos/acme/api/hooks/42")).willReturn(aResponse().withStatus(204)));

    client.deleteWebhook("acme/api", 42L);

    verify(deleteRequestedFor(urlPathEqualTo("/repos/acme/api/hooks/42")));
  }

  @Test
  void delete_missing_isNoOp() {
    stubFor(
        delete(urlPathEqualTo("/repos/acme/api/hooks/42")).willReturn(aResponse().withStatus(404)));

    assertThatCode(() -> client.deleteWebhook("acme/api", 42L)).doesNotThrowAnyException();
  }

  @Test
  void delete_otherFailure_isNotSwallowed() {
    stubFor(
        delete(urlPathEqualTo("/repos/acme/api/hooks/42")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client.deleteWebhook("acme/api", 42L))
        .isInstanceOf(VcsServerException.class);
  }

  private GitHubVcsProvider provider(WireMockRuntimeInfo wm) {
    var props = new GitHubProperties(wm.getHttpBaseUrl(), null, 256);
    return new GitHubVcsProvider(props, token -> "token", null);
  }

  private static String body(String name) {
    try (var in = GitHubWebhooksTest.class.getResourceAsStream("/wiremock/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Anonymous requests must not create a session. This runs on a real server because MockMvc does not
 * commit the response the way Tomcat does, so it cannot show the session being written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class AnonymousSessionTest {

  @LocalServerPort private int port;
  @Autowired private StringRedisTemplate redis;

  @Test
  void anonymousBrowserNavigation_createsNoSession() throws Exception {
    Set<String> before = redis.keys("*");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/test"))
            .header("Accept", "text/html")
            .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().allValues("Set-Cookie"))
        .noneMatch(cookie -> cookie.startsWith("SESSION="));
    assertThat(redis.keys("*")).isEqualTo(before);
  }
}

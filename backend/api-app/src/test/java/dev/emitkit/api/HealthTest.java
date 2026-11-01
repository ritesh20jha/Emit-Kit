package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")
@Import(RedisTestConfig.class)
class HealthTest {

  @LocalManagementPort private int managementPort;
  @Autowired private Environment environment;

  @Test
  void healthIsUpOnManagementPort() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void otherActuatorEndpointsNotExposed() throws Exception {
    assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
  }

  @Test
  void logPatternCarriesMdcKeys() {
    assertThat(environment.getProperty("logging.pattern.level"))
        .contains("%X{runId")
        .contains("%X{orgId");
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}

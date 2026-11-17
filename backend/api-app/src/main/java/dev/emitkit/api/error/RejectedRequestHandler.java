package dev.emitkit.api.error;

import dev.emitkit.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a firewall-rejected request (encoded separator, {@code ..}, backslash) with a problem.
 */
@Component
class RejectedRequestHandler implements RequestRejectedHandler {

  private final JsonMapper json;

  RejectedRequestHandler(JsonMapper json) {
    this.json = json;
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res, RequestRejectedException ex)
      throws IOException {
    // The firewall's message names the rejected input, so it stays out of the response.
    ProblemDetail pd =
        ProblemDetailsAdvice.problem(
            ErrorCode.VALIDATION_FAILED, "The request URL was rejected.", Map.of());
    res.setStatus(pd.getStatus());
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    res.getOutputStream().write(json.writeValueAsBytes(pd));
  }
}

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
 * Answers a request that the security firewall rejected (an encoded separator, a {@code ..}
 * segment, a backslash) with the standard problem body and VALIDATION_FAILED. Spring Security picks
 * this bean up in place of its bare 400. The firewall itself is unchanged.
 */
@Component
class RejectedRequestHandler implements RequestRejectedHandler {

  private final JsonMapper json;

  RejectedRequestHandler(JsonMapper json) {
    this.json = json;
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, RequestRejectedException rejected)
      throws IOException {
    // The firewall's message names the rejected input, so it stays out of the response.
    ProblemDetail pd =
        ProblemDetailsAdvice.problem(
            ErrorCode.VALIDATION_FAILED, "The request URL was rejected.", Map.of());
    response.setStatus(pd.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getOutputStream().write(json.writeValueAsBytes(pd));
  }
}

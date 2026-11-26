package dev.emitkit.api.jobs;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for the key re-wrap (ov section 3.1). Served on the management port only, like the
 * restore-drill probe. Answers the rows still below the latest key version, per column.
 */
@ManagementContextConfiguration(value = ManagementContextType.CHILD)
@Hidden
@RestController
class RewrapController {

  private final RewrapJob job;

  RewrapController(RewrapJob job) {
    this.job = job;
  }

  @PostMapping(path = "/internal/rewrap", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Long> rewrap() {
    return job.rewrapAll();
  }
}

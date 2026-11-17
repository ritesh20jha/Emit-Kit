package dev.emitkit.api.runs;

import dev.emitkit.domain.SdkVersionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The SDK versions a project has released. Callers check the project permission first. */
@Service
public class VersionService {

  private final SdkVersionRepository versions;

  VersionService(SdkVersionRepository versions) {
    this.versions = versions;
  }

  /** A project's released versions, newest first. */
  @Transactional(readOnly = true)
  public List<VersionResponse> list(String projectId) {
    return versions.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
        .map(v ->
            new VersionResponse(
                v.getVersion(),
                v.getChangeType(),
                v.getRunId(),
                v.getChangelog(),
                v.getCreatedAt()))
        .toList();
  }
}

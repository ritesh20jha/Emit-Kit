package dev.emitkit.api.spec;

import dev.emitkit.api.access.Permission;
import dev.emitkit.api.access.ProjectAccess;
import dev.emitkit.api.projects.SnapshotSummary;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.spec.diff.SpecDiffer;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ApiModelJson;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Lists a project's saved spec versions and diffs two of them. Both need {@link
 * Permission#SPEC_VIEW} on the project.
 */
@Service
public class SpecHistoryService {

  private final ProjectAccess projectAccess;
  private final SpecSnapshotRepository snapshots;
  private final SpecDiffer differ = new SpecDiffer();

  public SpecHistoryService(ProjectAccess projectAccess, SpecSnapshotRepository snapshots) {
    this.projectAccess = projectAccess;
    this.snapshots = snapshots;
  }

  /** The project's 100 newest snapshots, newest first. */
  public List<SnapshotSummary> listSnapshots(String projectId) {
    projectAccess.require(projectId, Permission.SPEC_VIEW);
    return snapshots.findTop100ByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
        .map(s ->
            new SnapshotSummary(
                s.getId(), s.getContentHash(), s.getInfoVersion(), s.getCreatedAt()))
        .toList();
  }

  /**
   * Diffs snapshot {@code fromId} (before) against {@code toId} (after). A snapshot that is missing
   * or belongs to another project is NOT_FOUND.
   */
  public SpecDiff diff(String projectId, String fromId, String toId) {
    projectAccess.require(projectId, Permission.SPEC_VIEW);
    ApiModel before = model(projectId, fromId);
    return differ.diff(before, model(projectId, toId));
  }

  private ApiModel model(String projectId, String snapshotId) {
    return snapshots
        .findById(snapshotId)
        .filter(s -> s.getProjectId().equals(projectId))
        .map(s -> ApiModelJson.fromJson(s.getApiModel()))
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "snapshot not found"));
  }
}

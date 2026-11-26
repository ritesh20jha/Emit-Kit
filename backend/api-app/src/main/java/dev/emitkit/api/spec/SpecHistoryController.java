package dev.emitkit.api.spec;

import dev.emitkit.api.projects.SnapshotSummary;
import dev.emitkit.spec.diff.SpecDiff;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The saved versions of a project's spec, and the diff between two of them. */
@RestController
@RequestMapping("/api/v1/projects/{id}/spec")
@Tag(name = "Project spec", description = "The API spec of a project, its snapshots and diffs.")
public class SpecHistoryController {

  private final SpecHistoryService history;

  public SpecHistoryController(SpecHistoryService history) {
    this.history = history;
  }

  @GetMapping("/snapshots")
  @Operation(summary = "List the saved versions of the spec, newest first")
  public List<SnapshotSummary> listSnapshots(@PathVariable("id") String id) {
    return history.listSnapshots(id);
  }

  @GetMapping("/diff")
  @Operation(summary = "Diff two saved versions of the spec")
  public SpecDiff diffSpec(
      @PathVariable("id") String id,
      @RequestParam(name = "from") String from,
      @RequestParam(name = "to") String to) {
    return history.diff(id, from, to);
  }
}

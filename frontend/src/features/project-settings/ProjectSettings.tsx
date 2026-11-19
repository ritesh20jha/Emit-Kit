import { useQuery } from "@tanstack/react-query";
import { type ProjectDetail, Panel, projectQuery, QueryBody } from "@/features/projects";
import { usePermission } from "@/lib/auth/permissions";
import { DangerZone } from "./DangerZone";
import { WebhookPanel } from "./WebhookPanel";

/** The project's settings: webhook, output mode (read-only for now) and the danger zone. */
export function ProjectSettings({ projectId }: { projectId: string }) {
  const project = useQuery(projectQuery(projectId));
  return (
    <QueryBody query={project} label="the settings" noAccess="view this project">
      {(detail) => <SettingsBody project={detail} />}
    </QueryBody>
  );
}

function SettingsBody({ project }: { project: ProjectDetail }) {
  const canEdit = usePermission("CONFIG_EDIT", project.orgId);
  const canDelete = usePermission("PROJECT_DELETE", project.orgId);
  const separate = project.outputMode === "SEPARATE";
  return (
    <div className="flex flex-col gap-4">
      <WebhookPanel project={project} canEdit={canEdit} />
      <Panel title="Output">
        <dl className="grid grid-cols-[max-content_1fr] items-center gap-x-6 gap-y-2 text-sm">
          <dt className="text-fg-muted">Mode</dt>
          <dd className="text-fg">{separate ? "Separate repository" : "Same repository"}</dd>
          {separate && (
            <>
              <dt className="text-fg-muted">Repository</dt>
              <dd className="font-mono text-fg">{project.outputRepoFullName}</dd>
            </>
          )}
        </dl>
        <p className="text-sm text-fg-muted">The output mode cannot be changed yet.</p>
      </Panel>
      <DangerZone project={project} canDelete={canDelete} />
    </div>
  );
}

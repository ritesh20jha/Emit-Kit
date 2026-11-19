import { type ProjectDetail, Panel } from "@/features/projects";
import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { Button, ConfirmDialog, NoAccessState, toast } from "@/ui";
import { useDeleteProject } from "./api";

/** Deleting asks for the repository's full name, typed exactly, and then returns to the org's projects. */
export function DangerZone({ project, canDelete }: { project: ProjectDetail; canDelete: boolean }) {
  const [open, setOpen] = useState(false);
  const remove = useDeleteProject(project.id, project.orgId);
  const navigate = useNavigate();

  async function confirmDelete() {
    // A failure rejects here, and the dialog stays open to show it.
    await remove.mutateAsync();
    await navigate({ to: "/orgs/$orgId", params: { orgId: project.orgId } });
    toast.success(`${project.repoFullName} was deleted`);
  }

  return (
    <Panel title="Danger zone" danger>
      {canDelete ? (
        <>
          <p className="text-sm text-fg-muted">Deleting removes this project from EmitKit. This cannot be undone from here.</p>
          <div>
            <Button variant="danger" onClick={() => setOpen(true)}>
              Delete project
            </Button>
          </div>
          <ConfirmDialog
            open={open}
            onOpenChange={setOpen}
            title="Delete this project?"
            description={`This removes ${project.repoFullName} from EmitKit.`}
            confirmLabel="Delete project"
            tone="danger"
            typedConfirmation={project.repoFullName}
            onConfirm={confirmDelete}
          />
        </>
      ) : (
        <NoAccessState action="delete this project" />
      )}
    </Panel>
  );
}

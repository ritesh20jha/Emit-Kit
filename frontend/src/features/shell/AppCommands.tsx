import { type QueryClient, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { useCallback, useMemo, useSyncExternalStore } from "react";
import { AppError, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/generated/schema";
import { qk } from "@/lib/api/queryKeys";
import { usePermission } from "@/lib/auth/permissions";
import { type Command, toast, useCommands, useShortcut } from "@/ui";

type OrgMembership = components["schemas"]["OrgMembership"];
type ProjectDetail = components["schemas"]["ProjectDetail"];
type ProjectSummary = components["schemas"]["ProjectSummary"];
type PageProjectSummary = components["schemas"]["PageProjectSummary"];

interface CachedProjects {
  /** The projects of the current org that the cache holds. */
  projects: ProjectSummary[];
  /** The org of the open project, when the cache says which one it is. */
  projectOrgId?: string;
}

/** The id of the project on a /projects/$id page, or undefined elsewhere. */
function useRouteProjectId(): string | undefined {
  return useRouterState({
    select: (state) => {
      for (const match of [...state.matches].reverse()) {
        // The typed router knows no project routes yet, so the route id is compared as a plain string.
        const routeId: string = match.routeId;
        if (routeId === "/projects/$id" || routeId.startsWith("/projects/$id/")) {
          const id = (match.params as { id?: unknown }).id;
          return typeof id === "string" ? id : undefined;
        }
      }
      return undefined;
    },
  });
}

/** Reads the cache only, so it never fetches. The project's org is never guessed: it is undefined unless the cache says. */
function readCachedProjects(queryClient: QueryClient, orgId?: string, projectId?: string): CachedProjects {
  if (orgId === undefined) return { projects: [] };
  // A partial key: `{}` matches every cached filter of this org's project lists.
  const byId = new Map<string, ProjectSummary>();
  for (const [, page] of queryClient.getQueriesData<PageProjectSummary>({ queryKey: qk.projects.list(orgId) })) {
    for (const project of page?.items ?? []) byId.set(project.id, project);
  }
  const detail = projectId === undefined ? undefined : queryClient.getQueryData<ProjectDetail>(qk.projects.one(projectId));
  const listed = projectId !== undefined && byId.has(projectId);
  return { projects: [...byId.values()], projectOrgId: detail?.orgId ?? (listed ? orgId : undefined) };
}

/**
 * The cached projects, re-read whenever the cache changes, so a list that loads while the palette is mounted
 * still appears in it. The snapshot is a string, so React re-renders only when the projects actually change.
 */
function useCachedProjects(orgId: string | undefined, projectId: string | undefined): CachedProjects {
  const queryClient = useQueryClient();
  // Stable, so React subscribes once rather than on every render.
  const subscribe = useCallback((notify: () => void) => queryClient.getQueryCache().subscribe(notify), [queryClient]);
  const getSnapshot = useCallback(
    () => JSON.stringify(readCachedProjects(queryClient, orgId, projectId)),
    [queryClient, orgId, projectId],
  );
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);
  return useMemo(() => JSON.parse(snapshot) as CachedProjects, [snapshot]);
}

/** Starts a run of the project and resolves to its id. A failed start throws the API error. */
async function startRun(projectId: string, dryRun: boolean): Promise<string> {
  const { data, error, response } = await api.POST("/projects/{id}/runs", {
    params: { path: { id: projectId } },
    body: { dryRun },
  });
  if (!response.ok || data === undefined) throw toAppError(response, error);
  return data.id;
}

interface AppCommandsProps {
  orgs: OrgMembership[];
  /** The org the shell shows: the one in the URL, or the default one. */
  currentOrgId?: string;
}

/**
 * Registers the shell's commands with the palette and binds its shortcuts. Project actions appear only on a project
 * page whose org the cache knows, and only for the permissions the user holds in that org.
 */
export function AppCommands({ orgs, currentOrgId }: AppCommandsProps) {
  const navigate = useNavigate();
  const projectId = useRouteProjectId();
  const { projects, projectOrgId } = useCachedProjects(currentOrgId, projectId);
  // Hooks cannot be skipped by a condition, so they run for "" when the project's org is unknown. No membership has
  // that id, so both checks are false and the actions stay hidden.
  const canTrigger = usePermission("RUN_TRIGGER", projectOrgId ?? "");
  const canViewSpec = usePermission("SPEC_VIEW", projectOrgId ?? "");

  // Org and project routes arrive with WEB-04 to WEB-07, so the typed router does not know these paths yet.
  const go = (to: string) => void navigate({ to: to as "/" });
  const runAndOpen = (dryRun: boolean) => {
    if (projectId === undefined) return;
    startRun(projectId, dryRun)
      .then((runId) => navigate({ to: `/runs/${runId}` as "/" }))
      .catch((error: unknown) =>
        toast.error(error instanceof AppError ? error : { message: "The run could not start. Try again." }),
      );
  };

  const commands: Command[] = [];
  if (currentOrgId !== undefined) {
    commands.push(
      { id: "go-projects", group: "Go to", label: "Projects", shortcut: "g p", run: () => go(`/orgs/${currentOrgId}`) },
      { id: "go-members", group: "Go to", label: "Members", run: () => go(`/orgs/${currentOrgId}/members`) },
      { id: "go-settings", group: "Go to", label: "Settings", run: () => go(`/orgs/${currentOrgId}/settings`) },
    );
  }
  for (const project of projects) {
    commands.push({
      id: `project-${project.id}`,
      group: "Go to",
      label: project.repoFullName,
      keywords: ["project", project.repoFullName],
      run: () => go(`/projects/${project.id}`),
    });
  }
  if (projectId !== undefined) {
    commands.push({ id: "go-runs", group: "Go to", label: "Runs", shortcut: "g r", run: () => go(`/projects/${projectId}/runs`) });
    if (canTrigger) {
      commands.push(
        { id: "trigger-run", group: "Actions", label: "Trigger run", run: () => runAndOpen(false) },
        { id: "dry-run", group: "Actions", label: "Dry run", run: () => runAndOpen(true) },
      );
    }
    if (canViewSpec) {
      commands.push({ id: "open-editor", group: "Actions", label: "Open editor", run: () => go(`/projects/${projectId}/editor`) });
    }
  }
  for (const org of orgs) {
    if (org.orgId === currentOrgId) continue;
    commands.push({ id: `switch-${org.orgId}`, group: "Org", label: `Switch to ${org.name}`, run: () => go(`/orgs/${org.orgId}`) });
  }

  useCommands(commands, [orgs, currentOrgId, projects, projectId, canTrigger, canViewSpec, navigate]);

  useShortcut("g p", () => {
    if (currentOrgId !== undefined) go(`/orgs/${currentOrgId}`);
  });
  useShortcut("g r", () => {
    if (projectId !== undefined) go(`/projects/${projectId}/runs`);
  });
  // Pages mark their search box with data-search. Without one, the key does nothing.
  useShortcut("/", () => document.querySelector<HTMLElement>("[data-search]")?.focus());

  return null;
}

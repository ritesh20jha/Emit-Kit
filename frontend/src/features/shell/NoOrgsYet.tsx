import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { Building, RefreshCw } from "lucide-react";
import { useEffect, useRef } from "react";
import { AppError, failureFeedback, toAppError } from "@/lib/api/appError";
import { api } from "@/lib/api/client";
import { qk } from "@/lib/api/queryKeys";
import { useMe } from "@/lib/auth/session";
import { Button, EmptyState } from "@/ui";
import { defaultOrgId, lastOrg } from "./lastOrg";

/**
 * `/` for a user with no org memberships. On first sign-in that is a race, not a fact: the backend syncs orgs from
 * GitHub after the login redirect, so the first `/me` can arrive before the sync has written anything. This view
 * runs the sync itself (POST /me/sync-orgs returns only once the rows are written), re-reads the session either way,
 * and opens the default org as soon as one appears. A 429 means a sync just ran, so the re-read alone is enough.
 */
export function NoOrgsYet() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: me } = useMe();
  const sync = useMutation({
    mutationFn: async (): Promise<void> => {
      const { error, response } = await api.POST("/me/sync-orgs");
      if (!response.ok) throw toAppError(response, error);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: qk.me }),
  });

  // One automatic sync per mount. The ref survives StrictMode's dev-only effect replay, so it is not sent twice.
  const started = useRef(false);
  const { mutate } = sync;
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    mutate();
  }, [mutate]);

  const orgId = me ? defaultOrgId(me.orgs, lastOrg.get()) : undefined;
  useEffect(() => {
    // The org page route is added by WEB-04/05, so the target is a plain href.
    if (orgId !== undefined) void navigate({ href: `/orgs/${orgId}`, replace: true });
  }, [orgId, navigate]);

  if (orgId !== undefined || sync.isIdle || sync.isPending) {
    return (
      <div aria-busy="true">
        <EmptyState icon={RefreshCw} title="Loading your organizations" description="Reading them from GitHub. This takes a few seconds the first time you sign in." />
      </div>
    );
  }

  const failed = sync.isError && !(sync.error instanceof AppError && sync.error.code === "RATE_LIMITED");
  return (
    <EmptyState
      icon={Building}
      title="No organizations yet"
      description={failed ? failureFeedback(sync.error).message : "Your GitHub organizations appear here once they are synced to EmitKit."}
      action={<Button variant="secondary" size="sm" onClick={() => mutate()}>Sync from GitHub</Button>}
    />
  );
}

import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import { useFormContext, useWatch } from "react-hook-form";
import { FormField, Input } from "@/ui";
import { specCheckQuery, type SpecCheck } from "./api";
import { CHECK_DELAY_MS, useDebouncedValue } from "./debounce";
import type { WizardValues } from "./schemas";

/** Step 2: the spec path, checked against the repository as it is typed, and the default branch. */
export function SpecStep({ orgId }: { orgId: string }) {
  const { control } = useFormContext<WizardValues>();
  const mode = useWatch({ control, name: "mode" });
  const repo = useWatch({ control, name: "repo" });
  const path = useDebouncedValue(useWatch({ control, name: "specPath" }).trim(), CHECK_DELAY_MS);
  const branch = useDebouncedValue(useWatch({ control, name: "branch" }).trim(), CHECK_DELAY_MS);
  // A new repository has no file yet, so only an existing one is checked.
  const checkable = mode === "existing" && repo !== "" && path !== "";
  const check = useQuery({ ...specCheckQuery(orgId, repo, path, branch), enabled: checkable });

  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-lg font-semibold text-fg">Spec</h2>
      <FormField
        name="specPath"
        label="Spec path"
        description={mode === "new" ? "EmitKit creates a starter spec at this path." : "The OpenAPI file in the repository."}
      >
        <Input autoComplete="off" spellCheck={false} />
      </FormField>
      {mode === "existing" && (
        <>
          <FormField name="branch" label="Default branch" description="Pre-filled from the repository.">
            <Input autoComplete="off" spellCheck={false} />
          </FormField>
          {checkable && <SpecCheckNote query={check} branch={branch} />}
        </>
      )}
    </section>
  );
}

/** What the file at the typed path looks like on the branch. */
function SpecCheckNote({ query, branch }: { query: UseQueryResult<SpecCheck, Error>; branch: string }) {
  if (query.isPending) return <p role="status" className="text-xs text-fg-subtle">Checking the file…</p>;
  if (query.isError) return <p className="text-xs text-status-failed">{query.error.message}</p>;
  const check = query.data;
  if (!check.found) return <p className="text-xs text-fg-muted">No file at this path on {branch}.</p>;
  if (!check.valid) {
    return <p className="text-xs text-status-failed">Found, but it is not a valid spec: {check.errors[0] ?? "see the spec"}</p>;
  }
  return <p className="text-xs text-fg-muted">Found “{check.title ?? "Untitled"}”, version {check.version}.</p>;
}

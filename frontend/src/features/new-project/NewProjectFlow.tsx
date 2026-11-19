import { useState } from "react";
import { FormProvider, useForm, useWatch } from "react-hook-form";
import { AppError, failureFeedback } from "@/lib/api/appError";
import { usePermission } from "@/lib/auth/permissions";
import { useMe } from "@/lib/auth/session";
import { Button, NoAccessState, PageHeader, Skeleton, toast } from "@/ui";
import { type ProjectDetail, useCreateProject } from "./api";
import { useNameCheck } from "./nameCheck";
import { OutputStep } from "./OutputStep";
import { RepoStep } from "./RepoStep";
import { SpecStep } from "./SpecStep";
import {
  defaultValues,
  type NewProjectSearch,
  type StepErrors,
  toCreateRequest,
  validateStep,
  type WizardStep,
  type WizardValues,
} from "./schemas";

const STEPS: readonly WizardStep[] = [1, 2, 3];
const STEP_LABELS: Record<WizardStep, string> = { 1: "Repository", 2: "Spec", 3: "Output" };

export interface NewProjectFlowProps {
  orgId: string;
  search: NewProjectSearch;
  /** Writes the next search. Each step change adds a history entry, so Back and the browser's Back both work. */
  onSearchChange: (next: NewProjectSearch) => void;
  /** Called with the created project. The route decides where it goes next. */
  onCreated: (project: ProjectDetail) => void;
}

/** The new-project wizard for members who may create projects. Others see a no-access state. */
export function NewProjectFlow(props: NewProjectFlowProps) {
  const me = useMe();
  const canCreate = usePermission("PROJECT_CREATE", props.orgId);
  // Until /me answers, the role is unknown: the wizard must not flash a no-access state at a member.
  if (me.isPending) {
    return (
      <div role="status" aria-busy="true" className="mx-auto flex w-full max-w-2xl flex-col gap-4">
        <span className="sr-only">Loading…</span>
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-64" />
      </div>
    );
  }
  if (!canCreate) return <NoAccessState action="create projects" />;
  return <Wizard {...props} />;
}

function Wizard({ orgId, search, onSearchChange, onCreated }: NewProjectFlowProps) {
  const form = useForm<WizardValues>({ defaultValues: defaultValues(search) });
  const create = useCreateProject(orgId);
  const owner = useMe().data?.orgs.find((org) => org.orgId === orgId)?.slug ?? "";
  const mode = useWatch({ control: form.control, name: "mode" });
  const name = useWatch({ control: form.control, name: "name" });
  const takenMessage = useNameCheck(orgId, owner, mode, name);
  const [conflict, setConflict] = useState<string>();
  const step = search.step;

  /** Writes a step. The form's values travel with it, so a refresh keeps what was typed at each step change. */
  function goTo(next: WizardStep) {
    onSearchChange({ ...form.getValues(), step: next });
  }

  /** Shows a step's errors on their fields. Returns true when there are none. */
  function showErrors(errors: StepErrors): boolean {
    form.clearErrors();
    for (const [field, message] of Object.entries(errors)) {
      form.setError(field as keyof WizardValues, { type: "validate", message });
    }
    return Object.keys(errors).length === 0;
  }

  function next() {
    if (showErrors(validateStep(step, form.getValues(), takenMessage))) goTo((step + 1) as WizardStep);
  }

  function finish() {
    const values = form.getValues();
    // Every step is checked, not only this one: a link can open step 3 with step 1 unfinished.
    const first = STEPS.find((candidate) => Object.keys(validateStep(candidate, values, takenMessage)).length > 0);
    if (first !== undefined) {
      showErrors(validateStep(first, values, takenMessage));
      if (first !== step) goTo(first);
      return;
    }
    setConflict(undefined);
    create.mutate(toCreateRequest(values, owner), {
      onSuccess: onCreated,
      onError: (error) => {
        // A 409 means the repository is already taken: the fix is on step 1, so the wizard goes back there.
        if (error instanceof AppError && error.status === 409) {
          setConflict(error.detail ?? "This repository is already in use.");
          if (step !== 1) goTo(1);
          return;
        }
        toast.error(failureFeedback(error));
      },
    });
  }

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-6">
      <PageHeader title="New project" description="Connect a repository and choose where its output goes." />
      <ol aria-label="Steps" className="flex flex-wrap gap-2 text-sm">
        {STEPS.map((number) => (
          <li
            key={number}
            aria-current={number === step ? "step" : undefined}
            className={number === step ? "font-medium text-fg" : "text-fg-muted"}
          >
            {number}. {STEP_LABELS[number]}
          </li>
        ))}
      </ol>
      <FormProvider {...form}>
        <form
          noValidate
          className="flex flex-col gap-6"
          onSubmit={(event) => {
            event.preventDefault();
            if (step === 3) finish();
            else next();
          }}
        >
          {step === 1 && conflict && (
            <p role="alert" className="rounded-control border border-status-failed px-3 py-2 text-sm text-status-failed">
              {conflict}
            </p>
          )}
          {step === 1 && <RepoStep orgId={orgId} takenMessage={takenMessage} />}
          {step === 2 && <SpecStep orgId={orgId} />}
          {step === 3 && <OutputStep orgId={orgId} owner={owner} />}
          <footer className="flex items-center justify-between gap-2 border-t border-border pt-4">
            {step > 1 ? (
              <Button variant="ghost" onClick={() => goTo((step - 1) as WizardStep)}>
                Back
              </Button>
            ) : (
              <span />
            )}
            {step < 3 ? (
              <Button type="submit">Next</Button>
            ) : (
              <Button type="submit" loading={create.isPending}>
                Create project
              </Button>
            )}
          </footer>
        </form>
      </FormProvider>
    </div>
  );
}

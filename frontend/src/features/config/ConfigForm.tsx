import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery } from "@tanstack/react-query";
import { useBlocker } from "@tanstack/react-router";
import { useCallback } from "react";
import { FormProvider, useForm, useWatch } from "react-hook-form";
import { type ProjectDetail, projectQuery, QueryBody } from "@/features/projects";
import { AppError, failureFeedback } from "@/lib/api/appError";
import { usePermission } from "@/lib/auth/permissions";
import { Button, ConfirmDialog, toast } from "@/ui";
import { type ConfigResponse, configQuery, useSaveConfig } from "./api";
import { ReadOnlyConfig } from "./ReadOnlyConfig";
import { type ConfigValues, configSchema, isConfigField, toRequest, toValues } from "./schema";
import { AiSection } from "./sections/AiSection";
import { BreakingPolicySection } from "./sections/BreakingPolicySection";
import { LanguagesSection } from "./sections/LanguagesSection";
import { LintPolicySection } from "./sections/LintPolicySection";
import { OutputsSection } from "./sections/OutputsSection";
import { PackagesSection } from "./sections/PackagesSection";
import { VersioningSection } from "./sections/VersioningSection";

/** The project's generation settings. Editors get the form; viewers get the same values as text. */
export function ConfigForm({ projectId, welcome = false }: { projectId: string; welcome?: boolean }) {
  const project = useQuery(projectQuery(projectId));
  return (
    <QueryBody query={project} label="the project" noAccess="view this project">
      {(detail) => <ConfigBody project={detail} welcome={welcome} />}
    </QueryBody>
  );
}

function ConfigBody({ project, welcome }: { project: ProjectDetail; welcome: boolean }) {
  const canEdit = usePermission("CONFIG_EDIT", project.orgId);
  const config = useQuery(configQuery(project.id));
  return (
    <QueryBody query={config} label="the configuration" noAccess="view this project">
      {(data) =>
        canEdit ? (
          <ConfigEditor projectId={project.id} config={data} welcome={welcome} />
        ) : (
          <ReadOnlyConfig config={data} />
        )
      }
    </QueryBody>
  );
}

function ConfigEditor({ projectId, config, welcome }: { projectId: string; config: ConfigResponse; welcome: boolean }) {
  const form = useForm<ConfigValues>({ resolver: zodResolver(configSchema), defaultValues: toValues(config) });
  const save = useSaveConfig(projectId);
  const { isDirty } = form.formState;
  const outputs = useWatch({ control: form.control, name: "outputs" });
  const languages = useWatch({ control: form.control, name: "sdkLanguages" });
  const hasSdk = outputs.includes("SDK");

  // Leaving with unsaved edits asks first. A successful save resets the form, so the next navigation is not blocked.
  const shouldBlock = useCallback(() => isDirty, [isDirty]);
  const blocker = useBlocker({ shouldBlockFn: shouldBlock, enableBeforeUnload: isDirty, withResolver: true });

  function submit(values: ConfigValues) {
    save.mutate(toRequest(values), {
      onSuccess: (saved) => {
        form.reset(toValues(saved));
        toast.success("Configuration saved");
      },
      onError: (error) => showSaveError(error),
    });
  }

  /** Puts each server field error on its field. Toasts the failure only when no error names a field of the form. */
  function showSaveError(error: unknown) {
    const fieldErrors = error instanceof AppError ? (error.fieldErrors ?? {}) : {};
    let placed = false;
    for (const [field, message] of Object.entries(fieldErrors)) {
      if (!isConfigField(field)) continue;
      form.setError(field, { type: "server", message });
      placed = true;
    }
    if (!placed) toast.error(failureFeedback(error));
  }

  return (
    <FormProvider {...form}>
      <form noValidate onSubmit={form.handleSubmit(submit)} className="flex flex-col gap-4">
        {welcome && (
          <div role="status" className="flex flex-col gap-1 rounded-card border border-border bg-bg-muted p-4">
            <h2 className="text-sm font-medium text-fg">Choose what to generate</h2>
            <p className="text-sm text-fg-muted">Pick the outputs for this project. You can change them at any time.</p>
          </div>
        )}
        <OutputsSection />
        {hasSdk && <LanguagesSection />}
        {hasSdk && languages.length > 0 && <PackagesSection languages={languages} />}
        <VersioningSection />
        <BreakingPolicySection />
        <LintPolicySection />
        {/* Keyed by version: a saved config remounts the AI section, so the replace input closes. */}
        <AiSection key={config.version} isSet={config.aiKeySet} />
        <div className="flex items-center justify-end gap-3 border-t border-border pt-4">
          <span role="status" className="mr-auto text-sm text-fg-muted">
            {isDirty ? "Unsaved changes" : ""}
          </span>
          <Button type="submit" disabled={!isDirty} loading={save.isPending}>
            Save
          </Button>
        </div>
      </form>
      <ConfirmDialog
        open={blocker.status === "blocked"}
        onOpenChange={(open) => {
          if (!open) blocker.reset?.();
        }}
        title="Discard unsaved changes?"
        description="The changes to this configuration have not been saved."
        confirmLabel="Discard changes"
        cancelLabel="Keep editing"
        tone="danger"
        onConfirm={async () => blocker.proceed?.()}
      />
    </FormProvider>
  );
}

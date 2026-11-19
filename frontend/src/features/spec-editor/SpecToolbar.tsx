import { Button, Kbd, Tooltip } from "@/ui";

const IS_MAC = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);

export interface SpecToolbarProps {
  dirty: boolean;
  saving: boolean;
  onSave: () => void;
  onSaveAndGenerate: () => void;
  /** Runs only when there are no unsaved edits. Otherwise the button says to save first. */
  onDryRun: () => void;
}

/** Save, Save & generate and Dry run, with the save state. Save actions are off until something changed. */
export function SpecToolbar({ dirty, saving, onSave, onSaveAndGenerate, onDryRun }: SpecToolbarProps) {
  const dryRun = (
    <Button variant="secondary" size="sm" aria-disabled={dirty || undefined} className={dirty ? "opacity-50" : undefined} onClick={() => !dirty && onDryRun()}>
      Dry run
    </Button>
  );

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button variant="secondary" size="sm" disabled={!dirty} loading={saving} onClick={onSave}>
        Save
      </Button>
      <Kbd>{IS_MAC ? "⌘S" : "Ctrl S"}</Kbd>
      <Button size="sm" disabled={!dirty} loading={saving} onClick={onSaveAndGenerate}>
        Save & generate
      </Button>
      {dirty ? <Tooltip content="Save first">{dryRun}</Tooltip> : dryRun}
      <span role="status" className="ml-auto text-sm text-fg-muted">
        {dirty ? "Unsaved changes" : "All changes saved"}
      </span>
    </div>
  );
}

import type { components } from "@/lib/api/generated/schema";

type CreateProjectRequest = components["schemas"]["CreateProjectRequest"];

export type WizardStep = 1 | 2 | 3;
export type RepoMode = "existing" | "new";
export type Visibility = "public" | "private";
export type OutputChoice = "same" | "separate";

/** The wizard's URL search params. Each step's values are written when the step changes, so a refresh reopens it. */
export interface NewProjectSearch {
  step: WizardStep;
  mode?: RepoMode;
  repo?: string;
  name?: string;
  visibility?: Visibility;
  specPath?: string;
  branch?: string;
  outputMode?: OutputChoice;
  outputRepo?: string;
}

/** The form's values. Text is "" when unset. Every field has a default, so the form is always complete. */
export interface WizardValues {
  mode: RepoMode;
  repo: string;
  name: string;
  visibility: Visibility;
  specPath: string;
  branch: string;
  outputMode: OutputChoice;
  outputRepo: string;
}

export type StepErrors = Partial<Record<keyof WizardValues, string>>;

/** The same shape the API accepts for a repository name. */
const NAME_PATTERN = /^[\w.-]+$/;
/** owner/name, as the API takes it. A repo in the URL is read back into a request, so it is checked here. */
const REPO_PATTERN = /^[\w.-]+\/[\w.-]+$/;

function oneOf<T extends string>(value: unknown, options: readonly T[]): T | undefined {
  return options.find((option) => option === value);
}

/**
 * The router reads each search value as JSON, so a typed `?name=2024` arrives as the number 2024.
 * Text is read back as text, and empty text is dropped.
 */
function asText(value: unknown): string | undefined {
  const text = typeof value === "number" ? String(value) : value;
  return typeof text === "string" && text !== "" ? text : undefined;
}

/** A repository param that is owner/name, with neither segment a path step ("." or ".."). Anything else is dropped. */
function asRepo(value: unknown): string | undefined {
  const text = asText(value);
  if (text === undefined || !REPO_PATTERN.test(text)) return undefined;
  const [owner, name] = text.split("/");
  return owner === "." || owner === ".." || name === "." || name === ".." ? undefined : text;
}

/** Reads the search params. Unknown values fall back to the defaults, so a stale link still opens the wizard. */
export function parseNewProjectSearch(search: Record<string, unknown>): NewProjectSearch {
  const step = Number(search.step);
  return {
    step: step === 2 || step === 3 ? step : 1,
    mode: oneOf(search.mode, ["existing", "new"] as const),
    repo: asRepo(search.repo),
    name: asText(search.name),
    visibility: oneOf(search.visibility, ["public", "private"] as const),
    specPath: asText(search.specPath),
    branch: asText(search.branch),
    outputMode: oneOf(search.outputMode, ["same", "separate"] as const),
    outputRepo: asRepo(search.outputRepo),
  };
}

/** The form's starting values: the URL's where it has them, the defaults elsewhere. */
export function defaultValues(search: NewProjectSearch): WizardValues {
  return {
    mode: search.mode ?? "existing",
    repo: search.repo ?? "",
    name: search.name ?? "",
    visibility: search.visibility ?? "private",
    specPath: search.specPath ?? "openapi.yaml",
    branch: search.branch ?? "main",
    outputMode: search.outputMode ?? "same",
    outputRepo: search.outputRepo ?? "",
  };
}

/** The repository the project will watch: the chosen one, or owner/name for a new one. */
export function targetRepo(values: WizardValues, owner: string): string {
  return values.mode === "new" ? `${owner}/${values.name.trim()}` : values.repo;
}

/**
 * The errors of one step, keyed by field. `takenMessage` is the live name check's result for a new
 * repository, so Next refuses a name that is already in use.
 */
export function validateStep(step: WizardStep, values: WizardValues, takenMessage?: string): StepErrors {
  const errors: StepErrors = {};
  if (step === 1) {
    if (values.mode === "existing" && values.repo === "") errors.repo = "Choose a repository.";
    if (values.mode === "new") {
      const name = values.name.trim();
      if (name === "") errors.name = "Enter a name for the new repository.";
      else if (!NAME_PATTERN.test(name)) errors.name = "Use letters, numbers, dots, dashes and underscores.";
      else if (takenMessage) errors.name = takenMessage;
    }
  }
  if (step === 2) {
    if (values.specPath.trim() === "") errors.specPath = "Enter the path to the spec file.";
    if (values.mode === "existing" && values.branch.trim() === "") errors.branch = "Enter the default branch.";
  }
  if (step === 3 && values.outputMode === "separate" && values.outputRepo === "") {
    errors.outputRepo = "Choose the repository that receives the output.";
  }
  return errors;
}

/** The API body for the wizard's values. The form's lowercase values become the API's enums here, and only here. */
export function toCreateRequest(values: WizardValues, owner: string): CreateProjectRequest {
  const isNew = values.mode === "new";
  const separate = values.outputMode === "separate";
  return {
    repo: targetRepo(values, owner),
    mode: isNew ? "NEW" : "EXISTING",
    specPath: values.specPath.trim(),
    // The backend ignores this for a new repository and uses the default branch the host gives it.
    defaultBranch: isNew ? "main" : values.branch.trim(),
    outputMode: separate ? "SEPARATE" : "APPEND",
    outputRepo: separate ? values.outputRepo : null,
    visibility: isNew ? (values.visibility === "public" ? "PUBLIC" : "PRIVATE") : null,
  };
}

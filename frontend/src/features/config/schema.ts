import { z } from "zod";
import type { components } from "@/lib/api/generated/schema";

type ConfigResponse = components["schemas"]["ConfigResponse"];
type ConfigRequest = components["schemas"]["ConfigRequest"];

export const OUTPUT_KINDS = ["SDK", "CLI", "MCP", "DOCS"] as const;
export const SDK_LANGUAGES = ["typescript", "python", "java"] as const;
export const VERSION_STRATEGIES = ["EMITKIT_MANAGED", "SPEC_VERSION"] as const;
export const BREAKING_GATES = ["ALLOW", "REQUIRE_APPROVAL", "BLOCK"] as const;
export const LINT_FAIL_ONS = ["NEVER", "ERROR", "WARNING"] as const;
/** The only provider the worker supports. The server stores a blank one as this. */
export const AI_PROVIDERS = ["gemini"] as const;

export type SdkLanguage = (typeof SDK_LANGUAGES)[number];

export const OUTPUT_LABELS: Record<(typeof OUTPUT_KINDS)[number], string> = {
  SDK: "SDK",
  CLI: "CLI",
  MCP: "MCP server",
  DOCS: "Docs",
};
export const LANGUAGE_LABELS: Record<SdkLanguage, string> = {
  typescript: "TypeScript",
  python: "Python",
  java: "Java",
};
export const VERSION_LABELS: Record<(typeof VERSION_STRATEGIES)[number], string> = {
  EMITKIT_MANAGED: "EmitKit managed",
  SPEC_VERSION: "Spec version",
};
export const BREAKING_LABELS: Record<(typeof BREAKING_GATES)[number], string> = {
  ALLOW: "Allow",
  REQUIRE_APPROVAL: "Require approval",
  BLOCK: "Block",
};
export const LINT_LABELS: Record<(typeof LINT_FAIL_ONS)[number], string> = {
  NEVER: "Never",
  ERROR: "On errors",
  WARNING: "On warnings",
};
export const PROVIDER_LABELS: Record<string, string> = { gemini: "Google Gemini" };

/** Options for a select or a checkbox group, in the order the values are given. */
export function choices<T extends string>(values: readonly T[], labels: Record<string, string>) {
  return values.map((value) => ({ value, label: labels[value] ?? value }));
}

const TEXT_MAX = 255;
/** The npm scope rule the server applies (ConfigValidator). */
const NPM_SCOPE = /^[a-z0-9-~][a-z0-9-._~]*$/;

/**
 * The first rule the output directory breaks, or null. Mirrors ConfigValidator on the server, so the same
 * message comes back whichever side catches it.
 */
export function outputDirProblem(outputDir: string): string | null {
  if (outputDir === "") return "Enter an output directory.";
  if (outputDir.includes("\\")) return "outputDir must use '/' separators, not backslashes.";
  if (outputDir.startsWith("/")) return "outputDir must be relative, without a leading slash.";
  if (!outputDir.endsWith("/")) return "outputDir must end with a slash.";
  if (outputDir.length > TEXT_MAX) return `outputDir must be at most ${TEXT_MAX} characters.`;
  for (const segment of outputDir.slice(0, -1).split("/")) {
    if (segment === "") return "outputDir must not contain empty segments.";
    if (segment === ".") return "outputDir must not contain '.' segments.";
    if (segment === "..") return "outputDir must not contain '..' segments.";
  }
  return null;
}

const text = z.string().trim().max(TEXT_MAX, `Use at most ${TEXT_MAX} characters.`);

/**
 * The form's rules. The cross-field ones mirror the server's PRJ-04 checks. Languages count only while the SDK
 * output is on, because the request sends none otherwise.
 */
export const configSchema = z
  .object({
    outputs: z.array(z.enum(OUTPUT_KINDS)).min(1, "Choose at least one output."),
    sdkLanguages: z.array(z.enum(SDK_LANGUAGES)),
    outputDir: text.superRefine((value, ctx) => {
      const problem = outputDirProblem(value);
      if (problem) ctx.addIssue({ code: "custom", message: problem });
    }),
    npmScope: text,
    pypiName: text,
    mavenGroupId: text,
    mavenArtifactId: text,
    versionStrategy: z.enum(VERSION_STRATEGIES),
    breakingGate: z.enum(BREAKING_GATES),
    lintFailOn: z.enum(LINT_FAIL_ONS),
    aiProvider: z.string(),
    aiModel: text,
    /** null keeps the stored key, "" removes it, anything else replaces it. */
    aiApiKey: z.string().nullable(),
  })
  .superRefine((value, ctx) => {
    const languages = value.outputs.includes("SDK") ? value.sdkLanguages : [];
    if (value.outputs.includes("SDK") && languages.length === 0) {
      ctx.addIssue({ code: "custom", path: ["sdkLanguages"], message: "Choose at least one language for the SDK." });
    }
    if (languages.includes("typescript") && value.npmScope !== "" && !NPM_SCOPE.test(value.npmScope)) {
      ctx.addIssue({
        code: "custom",
        path: ["npmScope"],
        message: "Use lowercase letters, digits, '-' or '.' only.",
      });
    }
    if (languages.includes("python") && value.pypiName === "") {
      ctx.addIssue({ code: "custom", path: ["pypiName"], message: "Enter the PyPI name for Python." });
    }
    if (languages.includes("java") && value.mavenGroupId === "") {
      ctx.addIssue({ code: "custom", path: ["mavenGroupId"], message: "Enter the Maven group ID for Java." });
    }
    if (languages.includes("java") && value.mavenArtifactId === "") {
      ctx.addIssue({ code: "custom", path: ["mavenArtifactId"], message: "Enter the Maven artifact ID for Java." });
    }
  });

export type ConfigValues = z.infer<typeof configSchema>;

/** Every field the form has, so a server field error can be matched to one. */
export const CONFIG_FIELDS = Object.keys(configSchema.shape) as readonly (keyof ConfigValues)[];

export function isConfigField(name: string): name is keyof ConfigValues {
  return (CONFIG_FIELDS as readonly string[]).includes(name);
}

/** Whether a language the server returned is one the form knows. */
export function isSdkLanguage(value: string): value is SdkLanguage {
  return (SDK_LANGUAGES as readonly string[]).includes(value);
}

/** The form's starting values for a saved config. Secrets never come back, so the key starts unchanged. */
export function toValues(config: ConfigResponse): ConfigValues {
  return {
    outputs: config.outputs,
    sdkLanguages: (config.sdkLanguages ?? []).filter(isSdkLanguage),
    outputDir: config.outputDir,
    npmScope: config.npmScope ?? "",
    pypiName: config.pypiName ?? "",
    mavenGroupId: config.mavenGroupId ?? "",
    mavenArtifactId: config.mavenArtifactId ?? "",
    versionStrategy: config.versionStrategy,
    breakingGate: config.breakingGate,
    lintFailOn: config.lintFailOn,
    aiProvider: config.aiProvider ?? AI_PROVIDERS[0],
    aiModel: config.aiModel ?? "",
    aiApiKey: null,
  };
}

const orNull = (value: string) => (value === "" ? null : value);

/** The request for these values. Languages are sent only with the SDK output, and blank optional text as null. */
export function toRequest(values: ConfigValues): ConfigRequest {
  return {
    outputs: values.outputs,
    sdkLanguages: values.outputs.includes("SDK") ? values.sdkLanguages : [],
    outputDir: values.outputDir,
    npmScope: orNull(values.npmScope),
    pypiName: orNull(values.pypiName),
    mavenGroupId: orNull(values.mavenGroupId),
    mavenArtifactId: orNull(values.mavenArtifactId),
    versionStrategy: values.versionStrategy,
    breakingGate: values.breakingGate,
    lintFailOn: values.lintFailOn,
    aiProvider: orNull(values.aiProvider),
    aiModel: orNull(values.aiModel),
    aiApiKey: values.aiApiKey,
  };
}

/** The config page's URL search. `welcome=1` comes from the new-project wizard and shows the outputs prompt. */
export interface ConfigSearch {
  welcome?: 1;
}

/** Reads the search params. Anything else is dropped, so a stale link still opens the page. */
export function parseConfigSearch(search: Record<string, unknown>): ConfigSearch {
  const welcome: 1 | undefined = search.welcome === 1 || search.welcome === "1" ? 1 : undefined;
  return welcome === undefined ? {} : { welcome };
}

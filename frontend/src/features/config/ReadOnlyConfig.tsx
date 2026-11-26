import type { ReactNode } from "react";
import { Panel } from "@/features/projects";
import type { ConfigResponse } from "./api";
import {
  BREAKING_LABELS,
  isSdkLanguage,
  LANGUAGE_LABELS,
  LINT_LABELS,
  OUTPUT_LABELS,
  PROVIDER_LABELS,
  VERSION_LABELS,
} from "./schema";

/** The settings as text, for a member who may view them but not change them. Shows the same rows the form does. */
export function ReadOnlyConfig({ config }: { config: ConfigResponse }) {
  const languages = (config.sdkLanguages ?? []).filter(isSdkLanguage);
  const sdk = config.outputs.includes("SDK");
  return (
    <Panel title="Generation settings">
      <dl className="grid grid-cols-[max-content_1fr] items-baseline gap-x-6 gap-y-2 text-sm">
        <Row label="Outputs">{config.outputs.map((output) => OUTPUT_LABELS[output]).join(", ")}</Row>
        <Row label="Output directory" mono>
          {config.outputDir}
        </Row>
        {sdk && (
          <Row label="SDK languages">{languages.map((language) => LANGUAGE_LABELS[language]).join(", ") || "None"}</Row>
        )}
        {languages.includes("typescript") && (
          <Row label="npm scope" mono>
            {config.npmScope || "No scope"}
          </Row>
        )}
        {languages.includes("python") && (
          <Row label="PyPI name" mono>
            {config.pypiName ?? "Not set"}
          </Row>
        )}
        {languages.includes("java") && (
          <>
            <Row label="Maven group ID" mono>{config.mavenGroupId ?? "Not set"}</Row>
            <Row label="Maven artifact ID" mono>{config.mavenArtifactId ?? "Not set"}</Row>
          </>
        )}
        <Row label="Versioning">{VERSION_LABELS[config.versionStrategy]}</Row>
        <Row label="Breaking-change policy">{BREAKING_LABELS[config.breakingGate]}</Row>
        <Row label="Lint policy">{LINT_LABELS[config.lintFailOn]}</Row>
        <Row label="AI provider">
          {config.aiProvider ? (PROVIDER_LABELS[config.aiProvider] ?? config.aiProvider) : "Not set"}
        </Row>
        <Row label="AI model">{config.aiModel || "Provider default"}</Row>
        <Row label="AI key">{config.aiKeySet ? "Key set" : "Not set"}</Row>
      </dl>
      <p className="text-sm text-fg-muted">You have view access. Ask an org admin to change these settings.</p>
    </Panel>
  );
}

function Row({ label, mono = false, children }: { label: string; mono?: boolean; children: ReactNode }) {
  return (
    <>
      <dt className="text-fg-muted">{label}</dt>
      <dd className={mono ? "break-all font-mono text-fg" : "text-fg"}>{children}</dd>
    </>
  );
}

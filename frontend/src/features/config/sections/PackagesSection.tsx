import { Panel } from "@/features/projects";
import { FormField, Input } from "@/ui";
import type { SdkLanguage } from "../schema";

/** The package names each chosen language publishes under. Each language adds its own fields. */
export function PackagesSection({ languages }: { languages: readonly SdkLanguage[] }) {
  return (
    <Panel title="Package names">
      {languages.includes("typescript") && (
        <FormField name="npmScope" label="npm scope" description="Without the @. Leave blank for no scope.">
          <Input className="font-mono" />
        </FormField>
      )}
      {languages.includes("python") && (
        <FormField name="pypiName" label="PyPI name">
          <Input className="font-mono" />
        </FormField>
      )}
      {languages.includes("java") && (
        <>
          <FormField name="mavenGroupId" label="Maven group ID">
            <Input className="font-mono" />
          </FormField>
          <FormField name="mavenArtifactId" label="Maven artifact ID">
            <Input className="font-mono" />
          </FormField>
        </>
      )}
    </Panel>
  );
}

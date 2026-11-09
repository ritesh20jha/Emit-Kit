import { CopyButton } from "./CopyButton";

export interface CodeBlockProps {
  code: string;
  /** Shown above the code, e.g. "yaml". Plain text: no syntax highlighting in this ticket. */
  language?: string;
  /** Accessible name of the code region. Defaults to "<language> code", or "Code" without a language. */
  label?: string;
}

/** Code sample with a copy action. The code area scrolls sideways, so it is focusable and named. */
export function CodeBlock({ code, language, label }: CodeBlockProps) {
  const name = label ?? (language ? `${language} code` : "Code");
  return (
    <div className="flex flex-col rounded-card border border-border bg-bg-subtle">
      <div className="flex items-center justify-between gap-2 border-b border-border px-3 py-1.5">
        <span className="font-mono text-xs text-fg-muted">{language}</span>
        <CopyButton value={code} label="Copy code" />
      </div>
      <pre role="region" aria-label={name} tabIndex={0} className="overflow-x-auto p-3 font-mono text-xs text-fg">
        <code>{code}</code>
      </pre>
    </div>
  );
}

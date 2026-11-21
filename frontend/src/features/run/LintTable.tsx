import { useQuery } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { QueryBody } from "@/features/projects";
import { cn } from "@/lib/utils/cn";
import { EmptyState, Table, TBody, Td, Th, THead, Tr } from "@/ui";
import { lintQuery } from "./api";

/** Errors first, then warnings, then info. */
const SEVERITY = {
  ERROR: { rank: 0, label: "Error", tone: "text-status-failed" },
  WARNING: { rank: 1, label: "Warning", tone: "text-status-awaiting" },
  INFO: { rank: 2, label: "Info", tone: "text-fg-muted" },
} as const;

/** The lint issues of the run's spec, by severity, with rule, location and message. */
export function LintTable({ runId }: { runId: string }) {
  const issues = useQuery(lintQuery(runId));
  return (
    <QueryBody query={issues} label="the lint issues" noAccess="view this run">
      {(rows) =>
        rows.length === 0 ? (
          <EmptyState icon={ShieldCheck} title="No lint issues" description="The spec passed lint with no findings." />
        ) : (
          <Table label="Lint issues">
            <THead><Tr><Th>Severity</Th><Th>Rule</Th><Th>Location</Th><Th>Message</Th></Tr></THead>
            <TBody>
              {[...rows].sort((a, b) => SEVERITY[a.severity].rank - SEVERITY[b.severity].rank).map((row, index) => (
                <Tr key={index}>
                  <Td className={cn("font-medium", SEVERITY[row.severity].tone)}>{SEVERITY[row.severity].label}</Td>
                  <Td className="font-mono text-xs">{row.ruleId}</Td>
                  <Td className="font-mono text-xs">{row.line ? `${row.jsonPath}:${row.line}` : row.jsonPath}</Td>
                  <Td>{row.message}</Td>
                </Tr>
              ))}
            </TBody>
          </Table>
        )
      }
    </QueryBody>
  );
}

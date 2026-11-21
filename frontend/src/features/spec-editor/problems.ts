import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { failureFeedback } from "@/lib/api/appError";
import { qk } from "@/lib/api/queryKeys";
import type { FeedbackError } from "@/ui";
import { lintQuery, type LintIssue } from "./api";
import { type ValidationIssue, validateSpec } from "./validation";

export type ProblemSeverity = "error" | "warning" | "info";

/** One finding in the text: from the server's lint or from the browser's OpenAPI validation. */
export interface Problem {
  id: string;
  severity: ProblemSeverity;
  message: string;
  /** Where in the document the finding applies, such as `$.info.title`. */
  path: string;
  /** 1-based line, or null when the finding has no place in the text. */
  line: number | null;
}

/** What the panel shows. A failed lint check is a notice beside the browser's results, which still show. */
export interface ProblemsView {
  status: "pending" | "ready";
  problems: Problem[];
  error: FeedbackError | null;
  retry: () => void;
}

/** Checks run once the text has been still for this long, so they follow pauses rather than keystrokes. */
const CHECK_DELAY_MS = 500;

const SEVERITY: Record<LintIssue["severity"], ProblemSeverity> = { ERROR: "error", WARNING: "warning", INFO: "info" };

/** Shared, so a view without problems has the same array identity each render. */
const NO_PROBLEMS: Problem[] = [];

/** The text as it stood CHECK_DELAY_MS ago. The first value is the text itself, so the first check starts at once. */
export function useSettledText(text: string): string {
  const [settled, setSettled] = useState(text);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(text), CHECK_DELAY_MS);
    return () => clearTimeout(timer);
  }, [text]);
  return settled;
}

/**
 * The problems in the text: lint from POST /lint and validation from the worker, merged. A check keeps its previous
 * result while the next one runs, so the list does not flicker as the user types.
 */
export function useProblems(text: string): ProblemsView {
  const settled = useSettledText(text);
  const lint = useQuery({ ...lintQuery(settled), placeholderData: keepPreviousData });
  // Both checks are pure functions of the text, so a result for a text never goes stale.
  const validation = useQuery({
    queryKey: qk.validation(settled),
    queryFn: () => validateSpec(settled),
    placeholderData: keepPreviousData,
    staleTime: Infinity,
  });

  const problems = useMemo(() => [...fromLint(lint.data ?? []), ...fromValidation(validation.data ?? [])].sort(bySeverityAndLine), [lint.data, validation.data]);

  const retry = () => {
    void lint.refetch();
    void validation.refetch();
  };
  if (lint.isPending || validation.isPending) return { status: "pending", problems: NO_PROBLEMS, error: null, retry };
  const failure = lint.error ?? validation.error;
  return { status: "ready", problems, error: failure && failureFeedback(failure), retry };
}

const RANK: Record<ProblemSeverity, number> = { error: 0, warning: 1, info: 2 };
const NO_LINE = Number.MAX_SAFE_INTEGER;

/** Errors, then warnings, then info; within a severity, by line. A problem with no line comes last of its severity. */
function bySeverityAndLine(a: Problem, b: Problem): number {
  return RANK[a.severity] - RANK[b.severity] || (a.line ?? NO_LINE) - (b.line ?? NO_LINE);
}

function fromLint(issues: LintIssue[]): Problem[] {
  return issues.map((issue, index) => ({ id: `lint-${index}`, severity: SEVERITY[issue.severity], message: issue.message, path: issue.jsonPath, line: issue.line }));
}

function fromValidation(issues: ValidationIssue[]): Problem[] {
  return issues.map((issue, index) => ({ id: `validation-${index}`, severity: "error", message: issue.message, path: issue.path, line: issue.line }));
}

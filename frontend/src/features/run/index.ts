import { lazy } from "react";

export { ChangesSummary } from "./ChangesSummary";
export { FilesTree } from "./FilesTree";
export { LintTable } from "./LintTable";
export { RunLogs } from "./RunLogs";
export { RunPage } from "./RunPage";
export { useRefreshRunOnStep } from "./api";

/** The diff viewer loads when a file is first opened, which keeps @codemirror/merge out of the main bundle. */
export const LazyFileDiff = lazy(() => import("./FileDiff").then((module) => ({ default: module.FileDiff })));

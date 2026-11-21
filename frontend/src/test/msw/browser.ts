import { setupWorker } from "msw/browser";
import { meHandler, vcsQuotaHandler } from "./handlers/me";
import { orgHandlers } from "./handlers/org";
import { projectHandlers } from "./handlers/projects";
import { runChangesHandler, runFileHandlers, runLogHandler } from "./handlers/run";
import { specHandlers } from "./handlers/spec";

/**
 * The browser worker the e2e build starts (VITE_MSW=1). It answers every GET the routes make with the same fixtures the
 * unit tests use, so each route renders populated content without a backend.
 */
export const worker = setupWorker(
  meHandler,
  vcsQuotaHandler,
  runChangesHandler,
  ...orgHandlers,
  ...projectHandlers,
  ...specHandlers,
  ...runFileHandlers,
  runLogHandler,
);

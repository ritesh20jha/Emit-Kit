import type { ErrorCode } from "./appError";

/** Plain-language message for each error code. Typed as a full record, so a new code cannot be left out. */
export const errorMessages: Record<ErrorCode, string> = {
  SPEC_CONFLICT: "Someone else changed this file. Reload to see their version.",
  SPEC_NOT_FOUND: "The spec file was not found in the repository.",
  SPEC_TOO_LARGE: "This spec is too large to process.",
  SPEC_INVALID: "The spec is not valid OpenAPI. Fix the errors and try again.",
  LINT_FAILED: "The spec has lint errors that block this action.",
  BREAKING_BLOCKED: "This change breaks the API and was blocked. Approve it or change the spec.",
  GENERATOR_ERROR: "A code generator failed. Try again, or check the run logs.",
  CHECK_FAILED: "The generated code failed its syntax check.",
  SANDBOX_TIMEOUT: "The build sandbox took too long and was stopped.",
  STORAGE_ERROR: "We could not save or read stored files. Try again shortly.",
  VCS_CONFLICT: "The repository changed while we were working. Try again.",
  VCS_AUTH: "GitHub access was revoked or expired. Reconnect GitHub and try again.",
  VCS_RATE_LIMITED: "GitHub rate limit reached. Try again later.",
  SECRETS_UNAVAILABLE: "The secrets service is unavailable. Try again shortly.",
  UNAUTHENTICATED: "Your session ended. Sign in again.",
  FORBIDDEN: "You don't have permission to do this.",
  NOT_FOUND: "This item was not found. It may have been deleted.",
  VALIDATION_FAILED: "Some fields are invalid. Check the highlighted fields.",
  CONFLICT: "This conflicts with the current state. Reload and try again.",
  RATE_LIMITED: "Too many requests. Wait a moment and try again.",
  NOT_IMPLEMENTED: "This feature is not available yet.",
  INTERNAL: "Something went wrong on our side. Try again, and contact support if it keeps happening.",
};

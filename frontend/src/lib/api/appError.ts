import { errorMessages } from "./errorMessages";

/** Every error code the backend returns (ov §5). Keep in step with the backend's ErrorCode enum. */
export const ERROR_CODES = [
  "SPEC_CONFLICT",
  "SPEC_NOT_FOUND",
  "SPEC_TOO_LARGE",
  "SPEC_INVALID",
  "LINT_FAILED",
  "BREAKING_BLOCKED",
  "GENERATOR_ERROR",
  "CHECK_FAILED",
  "SANDBOX_TIMEOUT",
  "STORAGE_ERROR",
  "VCS_CONFLICT",
  "VCS_AUTH",
  "VCS_RATE_LIMITED",
  "SECRETS_UNAVAILABLE",
  "FORBIDDEN",
  "NOT_FOUND",
  "VALIDATION_FAILED",
  "CONFLICT",
  "RATE_LIMITED",
  "NOT_IMPLEMENTED",
  "INTERNAL",
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

const KNOWN_CODES: ReadonlySet<string> = new Set(ERROR_CODES);

/** RFC 9457 members that become AppError fields rather than `extra`. */
const STANDARD_FIELDS = new Set(["type", "title", "status", "detail", "code", "fieldErrors"]);

/** An API failure with a stable code, a plain-language message, and any details the body carried. */
export class AppError extends Error {
  code: ErrorCode;
  status: number;
  detail?: string;
  fieldErrors?: Record<string, string>;
  extra: Record<string, unknown>;

  constructor(fields: {
    code: ErrorCode;
    status: number;
    detail?: string;
    fieldErrors?: Record<string, string>;
    extra?: Record<string, unknown>;
  }) {
    super(errorMessages[fields.code]);
    this.name = "AppError";
    this.code = fields.code;
    this.status = fields.status;
    this.detail = fields.detail;
    this.fieldErrors = fields.fieldErrors;
    this.extra = fields.extra ?? {};
  }
}

/** Maps an RFC 9457 problem body to an AppError. A missing or unknown `code` becomes INTERNAL. */
export function toAppError(res: Response, body: unknown): AppError {
  const problem = isRecord(body) ? body : {};
  const code: ErrorCode = isErrorCode(problem.code) ? problem.code : "INTERNAL";
  const detail = typeof problem.detail === "string" ? problem.detail : undefined;
  const fieldErrors = isRecord(problem.fieldErrors) ? (problem.fieldErrors as Record<string, string>) : undefined;
  const extra = Object.fromEntries(Object.entries(problem).filter(([key]) => !STANDARD_FIELDS.has(key)));
  return new AppError({ code, status: res.status, detail, fieldErrors, extra });
}

function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === "string" && KNOWN_CODES.has(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

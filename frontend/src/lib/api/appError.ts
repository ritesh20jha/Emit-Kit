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
  "UNAUTHENTICATED",
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
const STANDARD_FIELDS = new Set(["type", "title", "status", "detail", "code", "errors", "instance", "fieldErrors"]);

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
  const fieldErrors = toFieldErrors(problem.errors);
  const extra = Object.fromEntries(Object.entries(problem).filter(([key]) => !STANDARD_FIELDS.has(key)));
  return new AppError({ code, status: res.status, detail, fieldErrors, extra });
}

/** Builds field -> message from the backend's `errors: [{field, message}]`. Malformed entries are ignored. */
function toFieldErrors(errors: unknown): Record<string, string> | undefined {
  if (!Array.isArray(errors)) return undefined;
  const result: Record<string, string> = {};
  for (const entry of errors) {
    if (isRecord(entry) && typeof entry.field === "string" && typeof entry.message === "string") {
      result[entry.field] = entry.message;
    }
  }
  return Object.keys(result).length > 0 ? result : undefined;
}

function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === "string" && KNOWN_CODES.has(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

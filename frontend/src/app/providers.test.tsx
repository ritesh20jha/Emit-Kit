import { describe, expect, it } from "vitest";
import { AppError } from "@/lib/api/appError";
import { createQueryClient } from "./providers";

type ShouldRetry = (failureCount: number, error: unknown) => boolean;
const shouldRetry = createQueryClient().getDefaultOptions().queries?.retry as ShouldRetry;

const problem = (code: "NOT_FOUND" | "INTERNAL", status: number) => new AppError({ code, status });

describe("createQueryClient retry policy", () => {
  it("does not retry a 401, so the login redirect happens at once", () => {
    expect(shouldRetry(0, problem("INTERNAL", 401))).toBe(false);
  });

  it("does not retry a 404", () => {
    expect(shouldRetry(0, problem("NOT_FOUND", 404))).toBe(false);
  });

  it("retries a 500 while under the cap", () => {
    expect(shouldRetry(0, problem("INTERNAL", 500))).toBe(true);
    expect(shouldRetry(2, problem("INTERNAL", 500))).toBe(true);
  });

  it("stops retrying a 500 once the cap of three failures is reached", () => {
    expect(shouldRetry(3, problem("INTERNAL", 500))).toBe(false);
  });
});

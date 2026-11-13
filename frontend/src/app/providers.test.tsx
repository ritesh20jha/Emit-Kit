import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { AppError } from "@/lib/api/appError";
import { toast } from "@/ui";
import { AppProviders, createQueryClient } from "./providers";

afterEach(() => {
  toast.dismiss();
  delete document.documentElement.dataset.theme;
});

type ShouldRetry = (failureCount: number, error: unknown) => boolean;
const shouldRetry = createQueryClient().getDefaultOptions().queries?.retry as ShouldRetry;
const problem = (status: number) => new AppError({ code: "INTERNAL", status });

describe("createQueryClient retry policy", () => {
  it.each([
    [401, false],
    [404, false],
    [500, true],
  ])("a %i retried: %s", (status, retried) => {
    expect(shouldRetry(0, problem(status))).toBe(retried);
  });

  it("stops retrying a 500 at the cap of three failures", () => {
    expect(shouldRetry(2, problem(500))).toBe(true);
    expect(shouldRetry(3, problem(500))).toBe(false);
  });
});

test("the providers set the theme attribute and render toasts", async () => {
  render(
    <AppProviders queryClient={createQueryClient()}>
      <p>app</p>
    </AppProviders>,
  );
  await waitFor(() => expect(document.documentElement.dataset.theme).toBe("light"));

  act(() => {
    toast.success("Saved");
  });
  expect(await screen.findByText("Saved")).toBeInTheDocument();
});

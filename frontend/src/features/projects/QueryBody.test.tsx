import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import { QueryBody } from "./QueryBody";

test("a failed background refetch keeps the data on screen", async () => {
  let calls = 0;
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Probe() {
    const query = useQuery({
      queryKey: ["probe"],
      queryFn: async () => {
        calls += 1;
        if (calls > 1) throw new Error("offline");
        return "cached value";
      },
    });
    return (
      <QueryBody query={query} label="the probe" noAccess="view this">
        {(value) => <p>{value}</p>}
      </QueryBody>
    );
  }
  render(
    <QueryClientProvider client={queryClient}>
      <Probe />
    </QueryClientProvider>,
  );
  expect(await screen.findByText("cached value")).toBeInTheDocument();

  // TanStack batches observer notifications on a timer, so one more turn lets the failed refetch reach the screen.
  await act(async () => {
    await queryClient.refetchQueries({ queryKey: ["probe"] });
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
  expect(queryClient.getQueryState(["probe"])?.status).toBe("error");

  expect(calls).toBe(2);
  expect(screen.getByText("cached value")).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

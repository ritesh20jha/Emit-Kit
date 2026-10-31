import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render, screen } from "@testing-library/react";
import { AppProviders } from "@/app/providers";
import { createAppRouter } from "@/app/router";

describe("home route", () => {
  it("renders the EmitKit placeholder at /", async () => {
    const router = createAppRouter(createMemoryHistory({ initialEntries: ["/"] }));

    render(
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>,
    );

    expect(await screen.findByText("EmitKit")).toBeInTheDocument();
  });
});

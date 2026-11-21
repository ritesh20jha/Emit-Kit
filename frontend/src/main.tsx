import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AppProviders, createQueryClient } from "@/app/providers";
import { createAppRouter } from "@/app/router";
import "./styles/app.css";

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Root element #root not found in index.html");
}

/**
 * The e2e build (VITE_MSW=1) answers the API from fixtures in the browser. The condition is a build-time constant, so
 * the normal bundle drops this branch and the MSW code with it.
 */
async function enableMockApi(): Promise<void> {
  if (import.meta.env.VITE_MSW !== "1") return;
  const { worker } = await import("@/test/msw/browser");
  await worker.start({ quiet: true });
}

void enableMockApi().then(() => {
  const queryClient = createQueryClient();
  const router = createAppRouter(queryClient);

  createRoot(rootElement).render(
    <StrictMode>
      <AppProviders queryClient={queryClient}>
        <RouterProvider router={router} />
      </AppProviders>
    </StrictMode>,
  );
});

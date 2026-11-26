import type { Preview } from "@storybook/react-vite";
import { createMemoryHistory, createRootRoute, createRouter, RouterContextProvider } from "@tanstack/react-router";
import "../src/styles/app.css";

// Link-based stories (RouteTabs, Breadcrumbs) need a router. A memory router on a project page keeps them deterministic.
const storyRouter = createRouter({
  routeTree: createRootRoute(),
  history: createMemoryHistory({ initialEntries: ["/projects/p1/runs"] }),
});

type Theme = "light" | "dark";

const preview: Preview = {
  globalTypes: {
    theme: {
      description: "Colour theme",
      defaultValue: "light",
      toolbar: {
        title: "Theme",
        icon: "circlehollow",
        dynamicTitle: true,
        items: [
          { value: "light", title: "Light" },
          { value: "dark", title: "Dark" },
        ],
      },
    },
  },
  decorators: [
    (Story, context) => {
      const theme: Theme = context.globals.theme === "dark" ? "dark" : "light";
      document.documentElement.dataset.theme = theme;
      return (
        <RouterContextProvider router={storyRouter}>
          <div className="min-h-screen bg-bg p-4 text-fg">
            <Story />
          </div>
        </RouterContextProvider>
      );
    },
  ],
  parameters: {
    layout: "padded",
  },
};

export default preview;

import type { Preview } from "@storybook/react-vite";
import "../src/styles/app.css";

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
        <div className="min-h-screen bg-bg p-4 text-fg">
          <Story />
        </div>
      );
    },
  ],
  parameters: {
    layout: "padded",
  },
};

export default preview;

import type { StorybookConfig } from "@storybook/react-vite";

// The react-vite builder reuses vite.config.ts, so stories get the same Tailwind plugin and aliases as the app.
const config: StorybookConfig = {
  stories: ["../src/ui/**/*.stories.@(ts|tsx)"],
  framework: "@storybook/react-vite",
};

export default config;

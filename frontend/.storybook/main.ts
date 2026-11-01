import type { StorybookConfig } from "@storybook/react-vite";

// The react-vite builder reuses vite.config.ts, so stories get the same Tailwind plugin and aliases as the app.
const config: StorybookConfig = {
  stories: ["../src/ui/**/*.stories.@(ts|tsx)"],
  framework: "@storybook/react-vite",
  async viteFinal(viteConfig) {
    // Pin the TypeScript config. The repo-root tsconfig.json extends @Emitkit/config/tsconfig.base.json,
    // which comes from the legacy bun workspace and is not installed for this frontend-only install.
    // Storybook's virtual modules would pick that file up. Remove this override when the legacy TS code
    // is deleted (CUT-01).
    return { ...viteConfig, tsconfig: "tsconfig.json" };
  },
};

export default config;

import type { StorybookConfig } from "@storybook/react-vite";

// The react-vite builder reuses vite.config.ts, so stories get the same Tailwind plugin and aliases as the app.
const config: StorybookConfig = {
  stories: ["../src/ui/**/*.stories.@(ts|tsx)"],
  framework: "@storybook/react-vite",
  async viteFinal(viteConfig) {
    // Pin the TypeScript config. Otherwise Storybook's generated virtual modules fail to build because
    // tsconfig discovery reaches the monorepo root, whose "extends" target is not in this worktree.
    return { ...viteConfig, tsconfig: "tsconfig.json" };
  },
};

export default config;

import { configDefaults, defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config.ts";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: "jsdom",
      globals: true,
      setupFiles: ["./src/test/setup.ts"],
      include: ["src/**/*.test.{ts,tsx}", "eslint-rules/**/*.test.ts"],
      // Playwright specs live in e2e/ and must never run under Vitest.
      exclude: [...configDefaults.exclude, "e2e/**"],
    },
  }),
);

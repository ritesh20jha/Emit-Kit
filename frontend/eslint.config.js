import js from "@eslint/js";
import boundaries from "eslint-plugin-boundaries";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";
import noRawPalette from "./eslint-rules/no-raw-palette.js";

const SOURCE_EXTENSIONS = [".ts", ".tsx", ".js", ".jsx"];

// Element descriptors are folder-based, so the finer distinctions are file categories on top of
// the `feature` and `lib` elements: a feature's index.ts (its public entry point) and lib/utils.
const SAME_FEATURE = { feature: "{{from.element.captured.feature}}" };

export default tseslint.config(
  {
    ignores: [
      "dist",
      "node_modules",
      "src/routeTree.gen.ts",
      "eslint-rules/__fixtures__/**",
      "playwright-report",
      "test-results",
      "coverage",
      "storybook-static",
    ],
  },
  js.configs.recommended,
  { files: ["**/*.js"], languageOptions: { globals: globals.node } },
  ...tseslint.configs.strict,
  reactHooks.configs.flat.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    plugins: { boundaries },
    settings: {
      "boundaries/elements": [
        { type: "feature", pattern: "src/features/(*)/**", capture: ["feature"] },
        { type: "routes", pattern: "src/routes/**" },
        { type: "ui", pattern: "src/ui/**" },
        { type: "lib", pattern: "src/lib/**" },
      ],
      "boundaries/files": [
        { category: "entry", pattern: "**/src/features/(*)/index.{ts,tsx}" },
        { category: "utils", pattern: "**/src/lib/utils{,.*,/**}" },
      ],
      "import/resolver": {
        // Relative paths in the alias map resolve against the lint cwd (frontend/ for `pnpm lint`).
        alias: { map: [["@", "./src"]], extensions: SOURCE_EXTENSIONS },
        node: { extensions: SOURCE_EXTENSIONS },
      },
    },
    rules: {
      "boundaries/dependencies": [
        "error",
        {
          default: "disallow",
          policies: [
            { from: { element: { type: "routes" } }, allow: { to: { element: { type: ["feature", "ui", "lib"] } } } },
            { from: { element: { type: "feature" } }, allow: { to: { element: { type: ["ui", "lib"] } } } },
            // A feature may use its own internals, and only the entry points (index.ts) of other features.
            { from: { element: { type: "feature" } }, allow: { to: { element: { type: "feature", captured: SAME_FEATURE } } } },
            { from: { element: { type: "feature" } }, allow: { to: { element: { type: "feature" }, file: { categories: "entry" } } } },
            // UI may only use the lib/utils helpers, not the rest of lib.
            { from: { element: { type: "ui" } }, allow: { to: { element: { type: "lib" }, file: { categories: "utils" } } } },
            { from: { element: { type: "lib" } }, allow: { to: { element: { type: "lib" } } } },
          ],
        },
      ],
    },
  },
  {
    files: ["src/**/*.{ts,tsx}"],
    plugins: { emitkit: { rules: { "no-raw-palette": noRawPalette } } },
    rules: { "emitkit/no-raw-palette": "error" },
  },
);

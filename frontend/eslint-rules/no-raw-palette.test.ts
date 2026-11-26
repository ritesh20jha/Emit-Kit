import { RuleTester } from "eslint";
import { parser } from "typescript-eslint";
import rule from "./no-raw-palette.js";

const ruleTester = new RuleTester({
  languageOptions: {
    parser,
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

const rawPalette = (match: string) => ({ messageId: "rawPalette", data: { match } });

ruleTester.run("no-raw-palette", rule, {
  valid: [
    {
      filename: "src/ui/Button.tsx",
      code: `export const Button = () => <div className="bg-indigo-500" />;`,
    },
    {
      filename: "src/features/x/A.tsx",
      code: `export const A = () => <div className="bg-accent text-fg-muted" />;`,
    },
    {
      filename: "src/features/x/A.tsx",
      code: `const surface = "bg-surface text-fg-muted border-line";`,
    },
  ],
  invalid: [
    {
      filename: "src/features/x/A.tsx",
      code: `export const A = () => <div className="bg-indigo-500" />;`,
      errors: [rawPalette("bg-indigo-500")],
    },
    {
      filename: "src/features/x/A.tsx",
      code: `const size = "text-[10px]";`,
      errors: [rawPalette("text-[10px]")],
    },
    {
      filename: "src/features/x/A.tsx",
      code: "const cls = `bg-red-300 ${active}`;",
      errors: [rawPalette("bg-red-300")],
    },
    {
      filename: "src/features/x/A.tsx",
      code: `const cls = "text-red-500 border-blue-200";`,
      errors: [rawPalette("text-red-500"), rawPalette("border-blue-200")],
    },
  ],
});

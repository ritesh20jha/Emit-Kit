import { ESLint } from "eslint";
import { relative, resolve, sep } from "node:path";
import { describe, expect, it } from "vitest";

const frontendDir = process.cwd();
const fixturesDir = resolve(frontendDir, "eslint-rules/__fixtures__");
const configFile = resolve(frontendDir, "eslint.config.js");

describe("import boundaries", () => {
  it("reports only the forbidden imports in the fixture project", async () => {
    const eslint = new ESLint({ cwd: fixturesDir, overrideConfigFile: configFile });
    const results = await eslint.lintFiles(["src/**/*.{ts,tsx}"]);

    const boundaryRuleIdsByFile: Record<string, string[]> = {};
    for (const result of results) {
      const file = relative(fixturesDir, result.filePath).split(sep).join("/");
      boundaryRuleIdsByFile[file] = result.messages
        .map((message) => message.ruleId ?? "")
        .filter((ruleId) => ruleId.startsWith("boundaries/"));
    }

    expect(boundaryRuleIdsByFile["src/features/b/B.tsx"]).toEqual(["boundaries/dependencies"]);
    expect(boundaryRuleIdsByFile["src/ui/BadUi.tsx"]).toEqual(["boundaries/dependencies"]);
    expect(boundaryRuleIdsByFile["src/ui/BadLib.tsx"]).toEqual(["boundaries/dependencies"]);
    expect(boundaryRuleIdsByFile["src/features/b/C.tsx"]).toEqual([]);
    expect(boundaryRuleIdsByFile["src/features/a/index.ts"]).toEqual([]);
    expect(boundaryRuleIdsByFile["src/features/a/internal.ts"]).toEqual([]);
    expect(boundaryRuleIdsByFile["src/ui/GoodUi.tsx"]).toEqual([]);
    expect(boundaryRuleIdsByFile["src/lib/utils.ts"]).toEqual([]);
  });
});

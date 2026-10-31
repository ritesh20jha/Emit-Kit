# GEN-09 · MCP server generator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MCP server generator: templates and a `Generator` implementation whose output matches golden files and is byte-stable.

**Lane:** GEN · **Depends on:** [GEN-01](GEN-01-generator-spi.md), [GEN-00](GEN-00-fixture-specs.md) · **Size budget:** ~600 changed lines (hard limit 1000) · **Branch:** `ticket/gen-09`

**Spec:** v1 §6 table row for this output (dir `{out}/mcp/`)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create/modify in `backend/generators/mcp/`: `src/main/java/dev/emitkit/gen/mcp/` + `src/main/resources/templates/mcp/`
- Templates: `server.ts.mustache`, `tools.ts.mustache`, `package.json.mustache`, `tsconfig.json.mustache`, `README.md.mustache`
- Create: `JsonSchemaRenderer.java` (Schema → JSON Schema)
- Test: `backend/generators/mcp/src/test/java/.../McpGeneratorTest.java`; golden output under `src/test/resources/golden/` (excluded from PR size)

## Interfaces

- **Consumes:** `Generator`, `GeneratorContext`, `Templates`, `GoldenFiles` (GEN-01); `FixtureSpecs` (GEN-00); `NameStrategy` (SPEC-01).
- **Produces:**
- `McpGenerator` (`MCP`): `@modelcontextprotocol/sdk` server, stdio transport, one tool per operation; tool name `snake_case(operationId)` truncated to 64 chars; description = summary, else description, else `"{METHOD} {path}"`.
- `inputSchema`: `{type: "object", properties: {<params…>, body: <request schema>}, required: [...]}`; refs inlined (cycle → `{}` at the second visit).
- Env vars `API_BASE_URL`, `API_TOKEN`, `API_KEY` documented in README.

## Tasks

### Task 1: Golden output

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest @ValueSource(strings = {"petstore", "polymorphism"})
void matchesGolden(String fixture) {
  GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
}
@Test void outputIsByteStable() {
  var m = FixtureSpecs.load("petstore");
  assertThat(generator.generate(m, ctx())).isEqualTo(generator.generate(m, ctx()));
}
@Test void idIsMCP() { assertThat(generator.id()).isEqualTo(GeneratorId.MCP); }
@Test void everyInputSchemaIsValidJsonSchema() {
  var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  for (var tool : McpGenerator.tools(FixtureSpecs.load("polymorphism")))
    assertThatCode(() -> factory.getSchema(tool.inputSchemaJson())).doesNotThrowAnyException();
}
@Test void toolNamesAreUniqueAndAtMost64() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:mcp:test`

- [ ] **Step 3: Implement** — `JsonSchemaRenderer` with a visited set; tool JSON embedded in `tools.ts` with stable key order. First run with `-Dgolden.update=true`, **read every generated golden file in the diff before committing** — the golden files are the review surface for this PR.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:mcp:test`

- [ ] **Step 5: Commit** — `git commit -m "feat(gen): mcp server generator"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-09`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

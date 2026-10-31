# GEN-12 · Toolchain image + compile tests

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the toolchain image around check.sh and prove every generator's output for every fixture passes the strict compilers.

**Lane:** GEN · **Depends on:** [GEN-03](GEN-03-ts-sdk-types.md), [GEN-05](GEN-05-py-sdk-models.md), [GEN-07](GEN-07-java-sdk-models.md), [GEN-08](GEN-08-cli.md), [GEN-09](GEN-09-mcp.md), [SBX-02](SBX-02-check-script.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/gen-12`

**Spec:** v1 §5.6 (image, checks per language), ov §6 (compile layer)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `deploy/docker/toolchain/Dockerfile` (uses `check.sh` from SBX-02)
- Create: `deploy/docker/toolchain/vendor/` build step: a `gradle` stage that downloads Jackson 2 jars for `javac` classpath (no network at check time)
- Create: `backend/generators/compile-tests/build.gradle.kts`, `src/test/java/dev/emitkit/gen/compile/CompileAllFixturesIT.java`, `ToolchainContainer.java`
- Create: `.github/workflows/toolchain.yml` (build image, push to GHCR on main, run compile tests with `--runtime=runsc`)

## Interfaces

- **Consumes:** All generators; `check.sh` contract (SBX-02); `FixtureSpecs` (GEN-00).
- **Produces:**
- Image `emitkit/toolchain:{ver}` (`ver` = `deploy/docker/toolchain/VERSION`, starting `1`): Node 20 + `typescript`, `prettier`, `@types/node`, `commander@11`, `@modelcontextprotocol/sdk` pre-installed globally and linked via `NODE_PATH`; Python 3.12 + `ruff`, `httpx`, `pydantic`; Temurin 21 + `google-java-format` jar; user `10001`; entrypoint `/usr/local/bin/check.sh`.
- `ToolchainContainer.check(List<GeneratedFile>, Set<String> languages): Report` — builds the input tar per SBX-02 layout, runs the container (runtime from `emitkit.sandbox.runtime`, default `runc` in this module's tests locally, `runsc` in CI), returns parsed `report.json`.

## Tasks

### Task 1: Image

- [ ] **Step 1: Write the failing test**

```bash
# deploy/docker/toolchain/test.sh (run in CI after build)
docker run --rm --network none emitkit/toolchain:test sh -c 'tsc -v && ruff --version && javac -version && id -u' | grep -q 10001
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `bash deploy/docker/toolchain/test.sh`

- [ ] **Step 3: Implement** — multi-stage Dockerfile; pin every tool version; no package manager left in the final stage.

- [ ] **Step 4: Run it — expect PASS**

  Run: `bash deploy/docker/toolchain/test.sh`

- [ ] **Step 5: Commit** — `git commit -m "build(toolchain): toolchain image"`

### Task 2: Compile every fixture

- [ ] **Step 1: Write the failing test**

```java
@ParameterizedTest(name = "{0} / {1}") @MethodSource("fixturesTimesGenerators")
void generatedCodePassesChecks(String fixture, GeneratorId gen) {
  var files = generators.get(gen).generate(FixtureSpecs.load(fixture), ctx());
  var report = toolchain.check(files, languagesFor(gen));
  assertThat(report.results()).allSatisfy(r -> assertThat(r.exitCode()).as(r.tool() + "\n" + r.output()).isZero());
}
// fixtures: all 9 + big-200-ops; generators: TS_SDK, PY_SDK, JAVA_SDK, CLI, MCP
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :generators:compile-tests:test`

- [ ] **Step 3: Implement** — `fixturesTimesGenerators()` cartesian product; failures print the tool output.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :generators:compile-tests:test`

  Also: if a generator produces non-compiling code for a fixture, fix the generator in this PR only if the fix is under ~100 lines; otherwise open a follow-up ticket and mark that pair `@Disabled("GEN-xx follow-up")` — never ship with `reserved-words` disabled.

- [ ] **Step 5: Commit** — `git commit -m "test(gen): compile all fixtures in toolchain image"`

---

## Done when

- [ ] All tasks above are committed on `ticket/gen-12`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

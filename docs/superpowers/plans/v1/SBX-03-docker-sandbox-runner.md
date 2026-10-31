# SBX-03 · DockerSandboxRunner

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run one sandbox container per check under gVisor with every isolation flag from the spec, cleanup, concurrency limit and startup guard.

**Lane:** SBX · **Depends on:** [SBX-01](SBX-01-gvisor-spike.md), [SBX-02](SBX-02-check-script.md), [CORE-01](CORE-01-common-errors-ids.md) · **Size budget:** ~750 changed lines (hard limit 1000) · **Branch:** `ticket/sbx-03`

**Spec:** v1 §5.6 (all container settings, runtime check, cleanup, concurrency, tests)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/sandbox/SandboxRunner.java`, `SandboxRequest.java`, `SandboxResult.java`, `CancellationToken.java`, `SandboxInfraException.java`, `DockerSandboxRunner.java`, `SandboxProperties.java`, `SandboxStartupCheck.java`, `SandboxSweeper.java`
- Modify: `backend/worker-app/build.gradle.kts` (`docker-java-core`, `docker-java-transport-httpclient5`)
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/sandbox/DockerSandboxRunnerIT.java` (escape suite), `SandboxStartupCheckTest.java`

## Interfaces

- **Produces:**
```java
public interface SandboxRunner { SandboxResult run(SandboxRequest req, CancellationToken token); }
public record SandboxRequest(String image, Path inputTar, Duration timeout) {}
public record SandboxResult(Path outputTar, String reportJson, boolean timedOut) {}
public final class CancellationToken { public void cancel(); public boolean isCancelled(); public void onCancel(Runnable r); }
public class SandboxInfraException extends TransientException {}   // daemon unreachable, image missing, copy failed
```
- Properties `emitkit.sandbox.*`: `docker-host` (from SBX-01), `runtime` (`runsc` default | `runc`), `max-concurrent` (4), `timeout` (180s), `image`.
- HostConfig (exact): `withRuntime(runtime)`, `withNetworkMode("none")`, `withReadonlyRootfs(true)`, `withTmpFs(Map.of("/work", "rw,size=512m,uid=10001"))`, `withNanoCPUs(1_000_000_000L)`, `withMemory(1L<<30)`, `withMemorySwap(1L<<30)`, `withPidsLimit(256L)`, `withCapDrop(Capability.ALL)`, `withSecurityOpts(List.of("no-new-privileges"))`; container `withUser("10001")`, label `emitkit.sandbox=true`, `emitkit.run=<runId>`.
- Flow: `create` → `copyArchiveToContainer(in.tar → /work)` → `start` → wait up to timeout (polling token every 500 ms) → kill on timeout/cancel → `copyArchiveFromContainer(/work/out.tar, /work/report.json)` → `remove(force)` in `finally`.
- Semaphore `max-concurrent`, fair; waiting also observes the token.
- `SandboxStartupCheck`: `docker info` runtimes must include `runsc` when `runtime=runsc`; `runtime=runc` with profile `prod`/`staging` → startup fails ("runc is only allowed in dev/test").
- `SandboxSweeper` every 10 min removes containers labelled `emitkit.sandbox=true` older than 10 min.

## Tasks

### Task 1: Runner + escape suite

- [ ] **Step 1: Write the failing test**

```java
// Uses a tiny test image (alpine + a script reading /work/cmd) built in @BeforeAll; runtime from -Dsandbox.runtime (CI: runsc)
@Test void runsUnderGvisor() { assertThat(exec("dmesg").report()).containsIgnoringCase("gvisor"); }       // skipped when runtime=runc
@Test void noNetwork() { assertThat(exec("wget -T2 -q -O- http://example.com; echo $?").report()).endsWith("1"); }
@Test void cannotWriteOutsideWork() { assertThat(exec("touch /etc/x; echo $?").report()).doesNotEndWith("0"); }
@Test void runsAsUid10001() { assertThat(exec("id -u").report()).isEqualTo("10001"); }
@Test void killedAtTimeout() { var r = runner.run(req("sleep 30", Duration.ofSeconds(2)), new CancellationToken()); assertThat(r.timedOut()).isTrue(); assertThat(containersLabelled()).isEmpty(); }
@Test void pidsLimitEnforced() { }
@Test void cancelKillsContainer() { }
@Test void outputTarAndReportCopiedBack() { }
@Test void concurrencyLimited() { /* max-concurrent=1, two runs → second starts after first */ }
@Test void daemonUnreachable_isInfraException() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*DockerSandboxRunnerIT' -Dsandbox.runtime=runsc`

- [ ] **Step 3: Implement** — `DockerSandboxRunner` per Interfaces.

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*DockerSandboxRunnerIT' -Dsandbox.runtime=runsc`

- [ ] **Step 5: Commit** — `git commit -m "feat(sandbox): gvisor docker sandbox runner"`

### Task 2: Startup guard + sweeper

- [ ] **Step 1: Write the failing test**

```java
@Test void prodWithRuncRuntime_failsStartup() { }
@Test void runscMissingFromDocker_failsStartup() { }
@Test void devWithRunc_starts() { }
@Test void sweeperRemovesOldLabelledContainers() { }
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*SandboxStartupCheckTest'`

- [ ] **Step 3: Implement** — `SandboxStartupCheck` (`ApplicationRunner`), `SandboxSweeper` (`@Scheduled`).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*SandboxStartupCheckTest'`

- [ ] **Step 5: Commit** — `git commit -m "feat(sandbox): runtime startup check and sweeper"`

---

## Done when

- [ ] All tasks above are committed on `ticket/sbx-03`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

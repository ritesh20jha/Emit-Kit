# SBX-01 · Spike: gVisor + rootless Docker

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find out whether gVisor runs under a dedicated rootless Docker daemon with working CPU/memory/pids limits, and record the decision.

**Lane:** SBX · **Depends on:** — · **Size budget:** ~150 changed lines (hard limit 1000) · **Branch:** `ticket/sbx-01`

**Spec:** v1 §5.6 (separate daemon, spike, fallback)

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `docs/spikes/2026-gvisor-rootless.md` (result + decision)
- Create: `deploy/sandbox/setup-rootless-gvisor.sh`, `deploy/sandbox/self-check.sh`

## Interfaces

- **Produces:** a decision recorded in `docs/spikes/2026-gvisor-rootless.md`: **A** rootless daemon + `runsc`, or **B** fallback rootful dedicated daemon + `runsc`. Plus the socket path the worker uses: `emitkit.sandbox.docker-host` (e.g. `unix:///run/user/10010/docker.sock` for A, `unix:///run/emitkit-sandbox/docker.sock` for B). SBX-03 and OPS-01 read this.

## Tasks

### Task 1: Self-check script

- [ ] **Step 1: Write the failing test**

```bash
# deploy/sandbox/self-check.sh DOCKER_HOST=...
docker run --rm --runtime=runsc alpine dmesg | grep -qi gvisor            || fail "not gVisor"
docker run --rm --runtime=runsc --network none alpine wget -T2 -q example.com && fail "network reachable"
docker run --rm --runtime=runsc --memory 64m alpine sh -c 'cat /sys/fs/cgroup/memory.max' | grep -q 67108864 || fail "memory limit"
docker run --rm --runtime=runsc --pids-limit 32 alpine sh -c 'for i in $(seq 100); do sleep 5 & done; wait' && fail "pids limit"
docker run --rm --runtime=runsc --cpus 1 alpine true                     || fail "cpus flag"
echo "sandbox ok"
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `DOCKER_HOST=<sandbox socket> bash deploy/sandbox/self-check.sh`

- [ ] **Step 3: Implement** — `setup-rootless-gvisor.sh`: create user `emitkit-sandbox`, install `runsc`, `dockerd-rootless-setuptool.sh install`, add `runsc` runtime to that daemon's `daemon.json` (`"runtimes": {"runsc": {"path": "/usr/local/bin/runsc", "runtimeArgs": ["--platform=systrap"]}}`), enable cgroup v2 delegation (`systemd` `Delegate=cpu cpuset io memory pids`). If any check fails under rootless, implement option B in the same script behind `--mode rootful` and re-run.

- [ ] **Step 4: Run it — expect PASS**

  Run: `DOCKER_HOST=<sandbox socket> bash deploy/sandbox/self-check.sh`

  Also: write the spike doc: environment (kernel, distro, Docker, runsc versions), each check's result for A and B, decision, and known gaps.

- [ ] **Step 5: Commit** — `git commit -m "docs(spike): gvisor with a dedicated sandbox daemon"`

---

## Done when

- [ ] All tasks above are committed on `ticket/sbx-01`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.

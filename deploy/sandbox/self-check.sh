#!/usr/bin/env bash
# Checks the sandbox Docker daemon named by $DOCKER_HOST: containers run under gVisor (runsc) with no network,
# and the daemon enforces the production limits (--cpus 1 --memory 1g --pids-limit 256, spec 5.6).
# Exits 0 only if every check passes. The socket is usually owned by uid 10010, so run it with sudo:
#   sudo env DOCKER_HOST=unix:///run/user/10010/docker.sock bash deploy/sandbox/self-check.sh
set -euo pipefail

fail() { echo "self-check FAILED: $*" >&2; exit 1; }
# Pinned to the alpine 3.22 index digest (read from the registry on the authoring host, not pulled there).
IMAGE=alpine:3.22@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f
sb=(docker run --rm --runtime=runsc --network none)   # every sandbox container: gVisor, no network

[ -n "${DOCKER_HOST:-}" ] || fail "DOCKER_HOST is not set (export the sandbox socket, see docs/spikes/2026-gvisor-rootless.md)"
command -v docker >/dev/null 2>&1 || fail "docker CLI not found"
err=$(docker info 2>&1 >/dev/null) || fail "cannot reach a Docker daemon at $DOCKER_HOST: $err"

# 1. gVisor: dmesg inside a runsc container names gVisor.
out=$("${sb[@]}" "$IMAGE" dmesg 2>&1) || fail "runsc containers do not start: $out"
grep -qi gvisor <<<"$out" || fail "not gVisor"

# 2. No network: wget must FAIL. 125 = docker failed, 126/127 = wget missing or not runnable; none of those is a pass.
rc=0; "${sb[@]}" "$IMAGE" wget -T2 -q -O /dev/null example.com || rc=$?
case $rc in 125|126|127) fail "network check could not run (exit $rc)" ;; esac
[ "$rc" -ne 0 ] || fail "network reachable"

# 3. The daemon has cgroup memory, CPU quota and pids limits (rootless dockerd without delegation only warns).
lim=$(docker info --format '{{.MemoryLimit}} {{.CPUCfsQuota}} {{.PidsLimit}}' 2>&1) || fail "docker info failed: $lim"
[ "$lim" = "true true true" ] || fail "daemon cgroup limits not supported (MemoryLimit CPUCfsQuota PidsLimit = '$lim'; check delegation)"

# 4. Memory is enforced: a 256m allocation under --memory 64m must be OOM-killed (exit 137).
rc=0; out=$("${sb[@]}" --memory 64m --memory-swap 64m "$IMAGE" sh -c 'head -c 256m /dev/zero | tail' 2>&1) || rc=$?
case $rc in 125|126|127) fail "OOM check could not run (exit $rc): $out" ;; esac
[ "$rc" -eq 137 ] || fail "memory limit not enforced: 256m under --memory 64m exited $rc, expected 137"

# 5. Pids: --pids-limit 256 must stop 400 children. Fork in a subshell so "can't fork" ends only the subshell,
#    then count the tasks visible in /proc. What runsc counts toward the limit is unknown until this is run.
rc=0
# shellcheck disable=SC2016  # single quotes on purpose: the loop runs in the container's shell
out=$("${sb[@]}" --pids-limit 256 "$IMAGE" sh -c '(i=0; while [ $i -lt 400 ]; do sleep 30 & i=$((i+1)); done) 2>/dev/null; n=0; for p in /proc/[0-9]*; do n=$((n+1)); done; echo $n' 2>&1) || rc=$?
case $rc in 125|126|127) fail "pids check could not run (exit $rc): $out" ;; esac
case $out in ''|*[!0-9]*) fail "pids check printed no task count (exit $rc): $out" ;; esac
[ "$out" -lt 400 ] || fail "pids limit 256 not enforced: $out tasks (exit $rc)"

# 6. The production limit flags are accepted by runsc.
"${sb[@]}" --cpus 1 --memory 1g --pids-limit 256 "$IMAGE" true || fail "production limit flags (--cpus 1 --memory 1g --pids-limit 256) rejected"

echo "sandbox ok"

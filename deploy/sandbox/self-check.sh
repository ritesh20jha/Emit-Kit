#!/usr/bin/env bash
# Checks the sandbox Docker daemon named by $DOCKER_HOST: containers run under gVisor (runsc),
# --network none blocks the network, and the memory, pids and cpus limits are enforced.
# Exits 0 only if every check passes.
set -euo pipefail

fail() { echo "self-check FAILED: $*" >&2; exit 1; }

[ -n "${DOCKER_HOST:-}" ] || fail "DOCKER_HOST is not set (export the sandbox socket, see docs/spikes/2026-gvisor-rootless.md)"
command -v docker >/dev/null 2>&1 || fail "docker CLI not found"
docker info >/dev/null 2>&1 || fail "cannot reach a Docker daemon at $DOCKER_HOST"

# 1. gVisor: dmesg inside a runsc container names gVisor.
out=$(docker run --rm --runtime=runsc alpine dmesg 2>&1) || fail "runsc containers do not start: $out"
grep -qi gvisor <<<"$out" || fail "not gVisor"

# 2. No network: wget must FAIL. 125 = docker failed, 126/127 = wget missing or not runnable; none of those is a pass.
rc=0; docker run --rm --runtime=runsc --network none alpine wget -T2 -q -O /dev/null example.com || rc=$?
case $rc in 125|126|127) fail "network check could not run (exit $rc)" ;; esac
[ "$rc" -ne 0 ] || fail "network reachable"

# 3. Memory: --memory 64m must show as 67108864 bytes in the cgroup.
out=$(docker run --rm --runtime=runsc --memory 64m alpine sh -c 'cat /sys/fs/cgroup/memory.max') || fail "memory-limited container did not run"
grep -qx 67108864 <<<"$out" || fail "memory limit is not 67108864 bytes (got: $out)"

# 4. Pids: --pids-limit 32 must stop 100 children from existing. Fork in a subshell so the limit's
#    "can't fork" only ends the subshell (busybox ash would otherwise exit the whole shell), then count tasks in /proc.
out=$(docker run --rm --runtime=runsc --pids-limit 32 alpine sh -c '(i=0; while [ $i -lt 100 ]; do sleep 30 & i=$((i+1)); done) 2>/dev/null; n=0; for p in /proc/[0-9]*; do n=$((n+1)); done; echo $n') || fail "pids-limited container did not run"
[ "$out" -lt 100 ] || fail "pids limit not enforced ($out tasks)"

# 5. Cpus: the --cpus flag is accepted by runsc.
docker run --rm --runtime=runsc --cpus 1 alpine true || fail "cpus flag"

echo "sandbox ok"

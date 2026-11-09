#!/usr/bin/env bash
# Checks that the Docker daemon behind DOCKER_HOST runs containers under gVisor
# (runsc) with the limits from spec 5.6. Exits non-zero on the first failing check.
#   DOCKER_HOST=unix:///run/emitkit-sandbox/docker.sock bash deploy/sandbox/self-check.sh
set -euo pipefail

IMG=alpine
fail() { echo "FAIL: $*" >&2; exit 1; }
run() { docker run --rm --runtime=runsc "$@"; }  # stdout only: values are compared exactly

docker info >/dev/null 2>&1 || fail "no Docker daemon at ${DOCKER_HOST:-default socket}"

out=$(run $IMG dmesg) || fail "container did not start under runsc (docker error above)"
grep -qi gvisor <<<"$out" || fail "not gVisor"
echo "ok: gvisor"

out=$(run --network none $IMG sh -c 'wget -T2 -q example.com 2>/dev/null && echo REACHABLE || echo UNREACHABLE') \
  || fail "no-network container did not start (docker error above)"
[ "$out" = UNREACHABLE ] || fail "network reachable"
echo "ok: network none"

# gVisor exposes cgroup v1 paths inside the sandbox, not memory.max.
out=$(run --memory 64m $IMG cat /sys/fs/cgroup/memory/memory.limit_in_bytes) \
  || fail "memory-limited container did not start (docker error above)"
[ "$out" = 67108864 ] || fail "memory limit (memory.limit_in_bytes=$out)"
echo "ok: memory 64m"

# Fork failure is observed, not inferred from exit codes. Each iteration prints launchN
# before forking. Under --pids-limit 32 busybox sh dies silently (exit 2) at a fork, so the
# last launchN line is the fork that failed. Without a limit all 100 forks run: forked-100.
rc=0
out=$(run --pids-limit 32 $IMG sh -c 'echo started; i=0; while [ $i -lt 100 ]; do echo launch$i; sleep 5 & i=$((i+1)); done; echo forked-100; wait') || rc=$?
grep -qx started <<<"$out" || fail "pids-limited container did not start (docker error above)"
grep -qx forked-100 <<<"$out" && fail "pids limit not enforced (all 100 forks succeeded)"
last=$(grep -o '^launch[0-9]*$' <<<"$out" | tail -1 || true)
[ -n "$last" ] && [ "${last#launch}" -lt 99 ] && [ "$rc" -ne 0 ] \
  || fail "shell did not die at a fork (rc=$rc, last=${last:-none})"
echo "ok: pids-limit 32 (fork of child ${last#launch} failed, rc=$rc)"

out=$(run --cpus 1 $IMG sh -c 'echo $(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us) $(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us)') \
  || fail "cpus-limited container did not start (docker error above)"
[ "$out" = "100000 100000" ] || fail "cpu limit (quota/period=$out)"
echo "ok: cpus 1"

echo "sandbox ok"

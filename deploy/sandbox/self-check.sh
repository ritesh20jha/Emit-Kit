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

# Sandbox processes count against --pids-limit, so 32 leaves little room: the shell
# aborts or counts fewer than 100 forks. Without a limit all 100 forks succeed (ok=100).
out=$(run --pids-limit 32 $IMG sh -c 'echo started; ok=0; i=0; while [ $i -lt 100 ]; do sleep 5 & [ $? -eq 0 ] && ok=$((ok+1)); i=$((i+1)); done; echo ok=$ok; wait') || true
grep -q started <<<"$out" || fail "pids-limited container did not start (docker error above)"
grep -q 'ok=100' <<<"$out" && fail "pids limit not enforced (all 100 forks succeeded)"
echo "ok: pids-limit 32"

out=$(run --cpus 1 $IMG sh -c 'echo $(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us) $(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us)') \
  || fail "cpus-limited container did not start (docker error above)"
[ "$out" = "100000 100000" ] || fail "cpu limit (quota/period=$out)"
echo "ok: cpus 1"

echo "sandbox ok"

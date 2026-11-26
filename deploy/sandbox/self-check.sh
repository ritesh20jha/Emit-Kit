#!/usr/bin/env bash
# Checks that the Docker daemon behind DOCKER_HOST runs sandbox containers under SANDBOX_RUNTIME (runc by default,
# or runsc for gVisor) with the limits from spec 5.6. Exits non-zero on the first failing check.
#   DOCKER_HOST=unix:///run/emitkit-sandbox/docker.sock bash deploy/sandbox/self-check.sh
#   DOCKER_HOST=... SANDBOX_RUNTIME=runsc bash deploy/sandbox/self-check.sh
set -euo pipefail

IMG=alpine
RUNTIME=${SANDBOX_RUNTIME:-runc}
fail() { echo "FAIL: $*" >&2; exit 1; }
run() { docker run --rm --runtime="$RUNTIME" "$@"; }  # stdout only: values are compared exactly

case "$RUNTIME" in runc | runsc) ;; *) fail "SANDBOX_RUNTIME must be runc or runsc, got '$RUNTIME'" ;; esac
docker info >/dev/null 2>&1 || fail "no Docker daemon at ${DOCKER_HOST:-default socket}"

out=$(run $IMG echo started) || fail "container did not start under $RUNTIME (docker error above)"
[ "$out" = started ] || fail "container under $RUNTIME printed '$out'"
if [ "$RUNTIME" = runsc ]; then
  grep -qi gvisor <<<"$(run $IMG dmesg)" || fail "not gVisor"
  echo "ok: gvisor"
else
  echo "ok: runc"
fi

out=$(run --network none $IMG sh -c 'wget -T2 -q example.com 2>/dev/null && echo REACHABLE || echo UNREACHABLE') \
  || fail "no-network container did not start (docker error above)"
[ "$out" = UNREACHABLE ] || fail "network reachable"
echo "ok: network none"

# runc on a cgroup v2 host shows memory.max; gVisor (and cgroup v1 hosts) show the v1 memory.limit_in_bytes.
out=$(run --memory 64m $IMG sh -c 'cat /sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory/memory.limit_in_bytes') \
  || fail "memory-limited container did not start (docker error above)"
[ "$out" = 67108864 ] || fail "memory limit (read $out)"
echo "ok: memory 64m"

# Fork failure is inferred from the shell dying after a launchN marker. Each iteration prints launchN before
# forking, and the last launchN line is the fork that failed. Without a limit all 100 forks run: forked-100.
# Under --pids-limit 32 the shell exits with rc 2 at a fork: runc prints "can't fork" on stderr, gVisor dies
# silently (observed, not explained). SBX-03 must not depend on rc 2; this check only needs rc != 0.
rc=0
out=$(run --pids-limit 32 $IMG sh -c 'echo started; i=0; while [ $i -lt 100 ]; do echo launch$i; sleep 5 & i=$((i+1)); done; echo forked-100; wait') || rc=$?
grep -qx started <<<"$out" || fail "pids-limited container did not start (docker error above)"
grep -qx forked-100 <<<"$out" && fail "pids limit not enforced (all 100 forks succeeded)"
last=$(grep -o '^launch[0-9]*$' <<<"$out" | tail -1 || true)
[ -n "$last" ] && [ "${last#launch}" -lt 99 ] && [ "$rc" -ne 0 ] \
  || fail "shell did not die at a fork (rc=$rc, last=${last:-none})"
echo "ok: pids-limit 32 (fork of child ${last#launch} failed, rc=$rc)"

# cgroup v2 cpu.max is "quota period"; the v1 files give the same two numbers.
out=$(run --cpus 1 $IMG sh -c 'cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo $(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us) $(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us)') \
  || fail "cpus-limited container did not start (docker error above)"
[ "$out" = "100000 100000" ] || fail "cpu limit (quota/period=$out)"
echo "ok: cpus 1"

echo "sandbox ok ($RUNTIME)"

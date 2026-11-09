# SBX-01 spike: gVisor under a dedicated sandbox daemon

**Question:** does gVisor (`runsc`) run under a dedicated Docker daemon with working `--cpus`, `--memory` and `--pids-limit`? (spec 5.6)

**Decision: B, provisional.** Option A (rootless daemon + runsc) cannot run a runsc container with cgroup limits on this host, so it is ruled out. B (dedicated rootful daemon + runsc) passed every check, but only on the host's **system** daemon, which is a stand-in and not dedicated. The dedicated B setup was not executed (it needs root).

**Socket for the worker (`emitkit.sandbox.docker-host`):** `unix:///run/emitkit-sandbox/docker.sock` (B). The rootless path `unix:///run/user/10010/docker.sock` is not usable.

## Environment

Commands run 2026-11-09 on the spike host:

- Ubuntu 26.04.1 LTS, kernel 7.0.0-43-generic, x86_64, 14 CPUs, 14 GiB RAM.
- cgroup v2. `user@1000.service` is delegated (`Delegate=yes`, `cpu cpuset io memory pids`).
- `kernel.apparmor_restrict_unprivileged_userns = 1`.
- System daemon (`/var/run/docker.sock`): Docker 29.9.3, cgroup driver `systemd`, runtimes `runc`, `runsc`.
- Transient rootless daemon (option A probe, started as `ritesh`, stopped and verified gone): Docker 29.9.3, Rootless, cgroup driver `systemd`, storage overlayfs.
- runsc `release-20261102.0` (spec 1.2.1) at `/usr/bin/runsc`, platform `systrap`.
- `shellcheck` is not installed, so it was not run.

## Results per check

`deploy/sandbox/self-check.sh`, final version. "not reached" means the script stops at an earlier failing check.

| Check | A, production config (systemd cgroups) | A, `--ignore-cgroups` diagnostic | B stand-in (system daemon) |
|---|---|---|---|
| gVisor (`dmesg` has gVisor) | container does not start | ok | ok |
| `--network none` | not reached | ok (`UNREACHABLE`) | ok |
| `--memory 64m` (`memory.limit_in_bytes`) | not reached | FAIL: `15625715712` (host RAM, no limit) | ok: `67108864` |
| `--pids-limit 32` (shell dies at a fork) | not reached | FAIL: all 100 forks ran (`forked-100`, rc 0) | ok: shell dies at fork of child 0 or 1, rc 2 (varies per run) |
| `--cpus 1` (`cfs_quota_us cfs_period_us`) | not reached | FAIL: `-1 100000` | ok: `100000 100000` |

Final self-check runs (after the pids fix in the fix round): system daemon GREEN twice (`sandbox ok`, exit 0; pids fork failure at child 0 in one run and child 1 in the other). Negative controls on the system daemon: (a) pids limit removed from a copy gives `FAIL: pids limit not enforced (all 100 forks succeeded)`; (b) a copy where the shell exits after `started`, before any fork, gives `FAIL: shell did not die at a fork (rc=2, last=none)`. Nonexistent socket gives `FAIL: no Docker daemon`.

## Option A probe log

All attempts used a transient rootless daemon with `runsc` registered in its `daemon.json`:

1. Default systemd cgroup driver: `systemd error: Access denied ... requires interactive authentication`. runsc calls systemd over the system bus.
2. `native.cgroupdriver=cgroupfs`: dockerd logs `Running in rootless-mode without cgroups`, and runsc fails with `open /sys/fs/cgroup/cgroup.subtree_control: permission denied`.
3. Same, with `cgroup-parent` under `user.slice/.../user@1000.service`: same result. The rootless daemon ignores the parent without systemd.
4. Systemd driver plus runsc `--rootless`: `Rootless mode not supported with "create"`.
5. Systemd driver with `DBUS_SYSTEM_BUS_ADDRESS` pointed at the user bus: the access-denied error is gone. runsc debug log shows it joins `user.slice/docker-<id>.scope`, then fails with `FATAL ERROR: creating container: open /sys/fs/cgroup/cgroup.subtree_control: permission denied`.
6. runsc `--systemd-cgroup=false`: the Docker shim appends `--systemd-cgroup=true`, so the flag is overridden. Same failure as 5.
7. runsc `--ignore-cgroups` (diagnostic only): containers run, but no limits are applied (table above).

Conclusion: under rootless Docker, runsc cannot create its sandbox cgroup on this host. Fixing that needs root (system bus policy or a root-owned cgroup), which is out of scope for a rootless daemon.

## Other findings

- gVisor exposes the memory limit at `/sys/fs/cgroup/memory/memory.limit_in_bytes` (cgroup v1 layout). `memory.max` does not exist inside the sandbox. The brief's path was corrected.
- Sandbox processes (sentry, gofer) count against `--pids-limit`. On the system daemon, limits 8 and 16 fail to start the container, 24 fails to start it, 32 starts and the shell dies at the fork of child 0 or 1, and 48 and 64 run `seq`. The spec's limit of 256 was not measured. 32 is close to the floor.
- How the pids check observes the failure: busybox `sh` dies silently with exit 2 at a failed fork (no error text on stderr, verified). So the check prints `launchN` before each fork and asserts that the shell died after a launch marker, before `forked-100`, with rc != 0. gVisor does not expose the limit (`/sys/fs/cgroup/pids/pids.max` reads `max` even with `--pids-limit 32`), so the limit is observed through the failing fork, not read back from the cgroup.
- An earlier counter version (`sleep 5 & [ $? -eq 0 ] && ok=$((ok+1))`) was dead logic: `$?` after `&` is always 0. It is removed. An earlier `$(seq 100)` loop also gave a false result when the substitution's fork failed.

## Not executed (needs root)

`deploy/sandbox/setup-rootless-gvisor.sh` is written but has not been run. Verified without root: it refuses `--mode rootless` unless `--i-know-runsc-fails-rootless` is given, and it stops at the root check before any change. Steps in it that were not executed:

- creating user `emitkit-sandbox` (uid 10010) and installing `/usr/local/bin/runsc`;
- `--mode rootless` (refused by default, ruled out): subuid/subgid, `systemctl set-property user@10010.service Delegate=...`, `loginctl enable-linger`, `dockerd-rootless-setuptool.sh install`, the rootless `daemon.json`;
- `--mode rootful`: `/etc/emitkit-sandbox/daemon.json`, `emitkit-sandbox-docker.service`, `systemctl enable --now`, then a readiness loop on `docker -H unix:///run/emitkit-sandbox/docker.sock info` (30 s).

## Stand-in vs dedicated: what differs

The B results above come from the system daemon. The dedicated unit that the script writes differs in these ways. None of them was measured:

- Daemon and config: system `docker.service` (`dockerd -H fd://`, default data-root `/var/lib/docker`, exec-root `/var/run/docker`, bridge `docker0`, `runsc` at `/usr/bin/runsc` with no runtimeArgs). Dedicated unit: `--host unix:///run/emitkit-sandbox/docker.sock --group emitkit-sandbox`, data-root `/var/lib/emitkit-sandbox/docker`, exec-root `/run/emitkit-sandbox/exec`, `--bridge none`, `runsc` at `/usr/local/bin/runsc` with `--platform=systrap`, config from `/etc/emitkit-sandbox/daemon.json`.
- Readiness: the dedicated unit is `Type=simple`, not `Type=notify` (Docker's own unit uses notify). `systemctl enable --now` can return before dockerd listens, so the script waits for `docker info` on the socket. The readiness loop was not run.
- Image cache: the dedicated daemon has an empty data-root. `alpine` is not loaded by the script, so the self-check needs `docker -H unix:///run/emitkit-sandbox/docker.sock pull alpine` (network) or an image load before it runs.
- Socket access: the dedicated socket is group-restricted (`emitkit-sandbox`). The stand-in socket is the default one.
- Unit hardening: `KillMode=process`, `Restart=on-failure`, `Delegate=yes`. The system unit has its own settings.

## Known gaps

- B has not been measured on a dedicated daemon. Next step (root): `sudo bash deploy/sandbox/setup-rootless-gvisor.sh --mode rootful`, load or pull `alpine` into the dedicated daemon, then `DOCKER_HOST=unix:///run/emitkit-sandbox/docker.sock bash deploy/sandbox/self-check.sh`.
- The pids check observes the shell dying at a fork under the limit. It does not read the limit back from the cgroup, because gVisor does not expose it. Its wording is "fork failure observed", not "limit confirmed".
- The self-check covers only the spec's limit checks. The escape tests in spec 5.6 (no network, writes outside `/work`, uid 10001, timeout, fork limit under load) belong to SBX-03.
- The `--cpus` check reads the cfs quota, not CPU time. It does not prove CPU scheduling is capped.
- `--pids-limit 256` (the spec value) was not measured.
- `shellcheck` was not run on the scripts.

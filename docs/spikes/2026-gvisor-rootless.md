# SBX-01 spike: gVisor under a dedicated sandbox daemon

**Question:** does gVisor (`runsc`) run under a dedicated Docker daemon with working `--cpus`, `--memory` and `--pids-limit`? (spec 5.6)

**Decision: B, provisional.** Option A (rootless daemon + runsc) is ruled out on this host: kernel 7.0.0-43, runsc release-20261102.0, probed as uid 1000 (`ritesh`), not the dedicated `emitkit-sandbox` user. In every configuration tried it could not run a runsc container with cgroup limits. B (dedicated rootful daemon + runsc) passed every check, but only on the host's **system** daemon, which is a stand-in and not dedicated. The dedicated B setup was not executed (it needs root). B's socket is root-equivalent; see "Security under B".

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

The B column is `deploy/sandbox/self-check.sh` output on the system daemon. The A-diagnostic `pids` and `cpus` cells are not script output: they were run as individual commands after the script stopped at the memory FAIL, which is the script's first failure. The A-production column comes from ad hoc `runsc` runs in the probe log. "not reached" means the script stops at an earlier failing check.

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

1. Default systemd cgroup driver: `systemd error: Access denied ... requires interactive authentication`. Hypothesis, not verified in source: runsc calls systemd over the system bus. Step 5 is consistent with this.
2. `native.cgroupdriver=cgroupfs`: dockerd logs `Running in rootless-mode without cgroups`, and runsc fails with `open /sys/fs/cgroup/cgroup.subtree_control: permission denied`.
3. Same, with `cgroup-parent` under `user.slice/.../user@1000.service`: same result. The dockerd log says cgroups are disabled without systemd. Inference, not tested: the `cgroup-parent` value was therefore not used.
4. Systemd driver plus runsc `--rootless`: `Rootless mode not supported with "create"`.
5. Systemd driver with `DBUS_SYSTEM_BUS_ADDRESS` pointed at the user bus: the access-denied error is gone. runsc debug log shows it joins `user.slice/docker-<id>.scope`, then fails with `FATAL ERROR: creating container: open /sys/fs/cgroup/cgroup.subtree_control: permission denied`.
6. runsc `--systemd-cgroup=false`: the Docker shim appends `--systemd-cgroup=true`, so the flag is overridden. Same failure as 5.
7. runsc `--ignore-cgroups` (diagnostic only): containers run, but no limits are applied (table above).

Conclusion: in every configuration tried (steps 1 to 7), runsc could not create a sandbox cgroup with limits under rootless Docker on this host. Hypothesis, not tested: fixing it would need root (system bus policy or a root-owned cgroup), which is out of scope for a rootless daemon.

## Other findings

- gVisor exposes the memory limit at `/sys/fs/cgroup/memory/memory.limit_in_bytes` (cgroup v1 layout). `memory.max` does not exist inside the sandbox. The brief's path was corrected.
- Sandbox processes (sentry, gofer) count against `--pids-limit`. On the system daemon, limits 8 and 16 fail to start the container, 24 fails to start it, 32 starts and the shell dies at the fork of child 0 or 1, and 48 and 64 run `seq`. The spec's limit of 256 was not measured. 32 is close to the floor.
- How the pids check infers the failure: under `--pids-limit 32` the shell dies silently with exit 2 right after a `launchN` marker, with no error text on stderr (observed). The cause is not explained. Busybox usually prints `can't fork`, so the sandbox may be dying instead of the shell (hypothesis). The check prints `launchN` before each fork and asserts that the shell died after a launch marker, before `forked-100`, with rc != 0. SBX-03 must not depend on exit code 2. gVisor does not expose the limit (`/sys/fs/cgroup/pids/pids.max` reads `max` even with `--pids-limit 32`), so the limit is observed through the failing fork, not read back from the cgroup.
- An earlier counter version (`sleep 5 & [ $? -eq 0 ] && ok=$((ok+1))`) was dead logic: `$?` after `&` is always 0. It is removed. An earlier `$(seq 100)` loop also gave a false result when the substitution's fork failed.

## Not executed (needs root)

`deploy/sandbox/setup-rootless-gvisor.sh` is written but has not been run. Verified without root: it refuses `--mode rootless` unless `--i-know-runsc-fails-rootless` is given, and it stops at the root check before any change. Steps in it that were not executed:

- creating user `emitkit-sandbox` (uid 10010) and installing `/usr/local/bin/runsc`;
- `--mode rootless` (refused by default, ruled out): subuid/subgid, `systemctl set-property user@10010.service Delegate=...`, `loginctl enable-linger`, `dockerd-rootless-setuptool.sh install`, the rootless `daemon.json`;
- `--mode rootful`: `/etc/emitkit-sandbox/daemon.json`, `emitkit-sandbox-docker.service`, `systemctl enable --now`, then a readiness loop on `docker -H unix:///run/emitkit-sandbox/docker.sock info` (30 s).

## Stand-in vs dedicated: what differs

The B results above come from the system daemon. The dedicated unit that the script writes differs in these ways. None of them was measured:

- Daemon and config: system daemon (`/usr/bin/dockerd -H fd:// --containerd=/run/containerd/containerd.sock`, observed in `ps`; default data-root `/var/lib/docker`, exec-root `/var/run/docker`, bridge `docker0`, `runsc` at `/usr/bin/runsc` with no runtimeArgs). Dedicated unit: `--host unix:///run/emitkit-sandbox/docker.sock --group emitkit-sandbox`, data-root `/var/lib/emitkit-sandbox/docker`, exec-root `/run/emitkit-sandbox/exec`, `--bridge none`, `runsc` at `/usr/local/bin/runsc` with `--platform=systrap`, config from `/etc/emitkit-sandbox/daemon.json`.
- Readiness: the dedicated unit is `Type=simple`, not `Type=notify` (Docker's own unit uses notify). `systemctl enable --now` can return before dockerd listens, so the script waits for `docker info` on the socket. The readiness loop was not run.
- Image cache: the dedicated data-root is `/var/lib/emitkit-sandbox/docker`, which is likely empty on first start (inference). The script does not load `alpine`. `docker run` pulls a missing image automatically, so the first self-check run would need network access unless the image is loaded first. Not tested.
- Shared containerd and image store (untested expectation): the dedicated unit does not pin `--containerd`, and the system daemon uses `/run/containerd/containerd.sock`. The dedicated daemon may therefore use the host's containerd. The added `--containerd-namespace emitkit-sandbox` and `--containerd-plugins-namespace emitkit-sandbox-plugins` should keep its image and container metadata apart from the host's (`moby` namespace). The content blobs may still be shared. The root run must confirm which containerd is used (`ctr namespaces ls`) and that `docker images` on the dedicated socket does not list the host's images.
- Firewall: `--iptables=false --ip6tables=false` are set in the unit. The stand-in leaves the system daemon's iptables handling unchanged. No effect was measured.
- Socket access: the dedicated socket is group-restricted (`emitkit-sandbox`). The stand-in socket is the default one.
- Unit hardening: `KillMode=process`, `Restart=on-failure`, `Delegate=yes`. The system unit has its own settings.

## Security under B

- The dedicated socket is root-equivalent. Anyone who can use `/run/emitkit-sandbox/docker.sock` (group `emitkit-sandbox`) can ask dockerd, which runs as root, to start containers. Such a client can then ask for `--privileged` or host mounts such as `-v /:/host`. This is how Docker works. It was not run in this spike (not tested, a documented Docker property). The spike's own user `ritesh` is already in the `docker` group (from `id`), which is the same root-equivalence on the system daemon today.
- gVisor is the only isolation boundary under B, and only for containers that actually run under runsc. The dedicated `daemon.json` sets `"default-runtime": "runsc"` as defence in depth. A client can still request `--runtime=runc`. Blocking that needs a Docker authorization plugin, which is out of scope here. Spec 5.6 allows runc only for the dev and test profiles.
- Who may join group `emitkit-sandbox` is an OPS-01 decision. The unit's `--group` only limits the socket to that group. It does not limit what the group can do.

## Known gaps

- B has not been measured on a dedicated daemon. Next step (root): `sudo bash deploy/sandbox/setup-rootless-gvisor.sh --mode rootful`. Then, before the self-check, confirm the dedicated daemon does not list the host's images (`docker -H unix:///run/emitkit-sandbox/docker.sock images`) and run `ctr namespaces ls` to see which containerd namespaces exist. Pull or load `alpine` into the dedicated daemon so the check does not pull from the network. Then run `DOCKER_HOST=unix:///run/emitkit-sandbox/docker.sock bash deploy/sandbox/self-check.sh`. To measure pids headroom, also run a copy of the check with `--pids-limit 256` in place of 32.
- The pids check infers a fork failure from the shell dying after a `launchN` marker. It does not read the limit back from the cgroup, because gVisor does not expose it. The wording is "fork failure inferred", not "limit confirmed". The exit code 2 is observed but unexplained, so SBX-03 must not depend on it.
- The self-check covers only the spec's limit checks. The escape tests in spec 5.6 (no network, writes outside `/work`, uid 10001, timeout, fork limit under load) belong to SBX-03.
- The `--cpus` check reads the cfs quota, not CPU time. It does not prove CPU scheduling is capped.
- `--pids-limit 256` (the spec value) was not measured.
- `shellcheck` was not run on the scripts.

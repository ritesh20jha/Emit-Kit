# SBX-01 spike: gVisor (runsc) with a dedicated sandbox Docker daemon

**Status: PENDING.** Nothing below has been run on the target host. No check has a result. The environment
facts are observed on the authoring host, and the release facts are verified read-only, as labelled.

**Decision: PENDING**

## Environment

Observed on the authoring host (not yet the target host):

| Item | Value |
| --- | --- |
| Kernel | `7.0.0-38-generic` (`uname -r`) |
| Distro | Ubuntu 26.04.1 LTS (`/etc/os-release`) |
| Docker | client 29.9.2; host daemon 29.9.2, cgroup version 2, runtimes: `runc` only |
| Host daemon limit fields | `docker info` MemoryLimit/CPUCfsQuota/PidsLimit = `true true true` (the host daemon, not the sandbox) |
| cgroup v2 | yes (`cgroup2fs`; controllers: cpuset cpu io memory hugetlb pids rdma misc dmem) |
| Unprivileged userns | `kernel.apparmor_restrict_unprivileged_userns=1` (can block rootlesskit; record it if it does) |
| dbus user session | `/usr/lib/systemd/user/dbus.socket` present |
| containerd | `/usr/bin/containerd` present (the sandbox containerd is separate) |
| uid/gid 10010 | free on the authoring host |
| runsc | not installed |
| shellcheck | not on the host; ran `-S style` clean through the cached `koalaman/shellcheck:stable` image |

Release facts, verified read-only on the authoring host:

| Item | Value |
| --- | --- |
| gVisor release pinned | `20261026` (newest confirmed: `gvisor.tar.bz2` and `.sha512` return 200; 20261027 through 20261101 return 404) |
| Archive | `gvisor.tar.bz2` downloaded to scratch; `sha512sum -c` OK against the vendor's `.sha512` (integrity only, same origin) |
| Archive contents | `runsc`, `containerd-shim-runsc-v1`, `gvisor-bin/` (helpers) |
| `runsc` flags (grep of the binary) | `--systemd-cgroup`, `--ignore-cgroups`, `--platform` present |
| `runsc --version` | **NOT OBSERVED.** Executing the downloaded binary was denied during this session. Run `runsc --version` on the target host and record it here. |
| Self-check image | `alpine:3.22@sha256:2e0b81d6…eb55f` (index digest from the registry via `docker buildx imagetools`, not pulled) |

## Sockets and OPS-01 facts

| Option | `emitkit.sandbox.docker-host` | Worker access |
| --- | --- | --- |
| A, rootless (default) | `unix:///run/user/10010/docker.sock` | Run the worker as uid 10010 (`user: "10010:10010"`). `/run/user/10010` is 0700, so group membership is not enough. |
| B, rootful dedicated | `unix:///run/emitkit-sandbox/docker/docker.sock` | `group_add: ["10010"]` (socket group `emitkit-sandbox`, gid 10010). Mount the directory `/run/emitkit-sandbox/docker`, not the socket file. |

- uid and gid are fixed at 10010 (`useradd --uid 10010`, `groupadd --gid 10010`). Both modes use the same group.
- Mount directories, not socket files: a daemon can recreate its socket, and a single-file bind mount then goes stale.
- Mode A exposure: the `/run/user/10010` directory also holds the user's systemd bus socket. Mounting the directory gives the worker that socket too. Recorded as a gap.
- Mode B: `RuntimeDirectoryPreserve=yes` keeps `/run/emitkit-sandbox` across stops.

## How to run

```sh
# Dry run (prints the commands; changes nothing; no root needed)
deploy/sandbox/setup-rootless-gvisor.sh --dry-run
deploy/sandbox/setup-rootless-gvisor.sh --mode rootful --dry-run

# Option A (rootless). Re-running restarts user@10010 and stops its running containers.
sudo deploy/sandbox/setup-rootless-gvisor.sh
sudo env DOCKER_HOST=unix:///run/user/10010/docker.sock bash deploy/sandbox/self-check.sh

# Option B (rootful, only if A fails a check). Re-running restarts the sandbox daemons.
sudo deploy/sandbox/setup-rootless-gvisor.sh --mode rootful
sudo env DOCKER_HOST=unix:///run/emitkit-sandbox/docker/docker.sock bash deploy/sandbox/self-check.sh
```

## Results

Each check in `deploy/sandbox/self-check.sh` (all containers run with `--runtime=runsc --network none`):

| # | Check | Pass condition |
| --- | --- | --- |
| 1 | gVisor | `dmesg` names gVisor |
| 2 | no network | `wget example.com` fails (exit 125/126/127 count as "could not run") |
| 3 | daemon cgroup limits | `docker info` MemoryLimit, CPUCfsQuota and PidsLimit all `true` |
| 4 | memory enforced | `head -c 256m /dev/zero \| tail` under `--memory 64m --memory-swap 64m` exits 137 |
| 5 | pids enforced | 400 forks under `--pids-limit 256` leave fewer than 400 tasks in `/proc` |
| 6 | production flags | `--cpus 1 --memory 1g --pids-limit 256` accepted |

### Option A (rootless)

| Check | Result |
| --- | --- |
| 1 gVisor | NOT RUN — pending execution on the target host |
| 2 no network | NOT RUN — pending execution on the target host |
| 3 daemon cgroup limits | NOT RUN — pending execution on the target host |
| 4 memory enforced | NOT RUN — pending execution on the target host |
| 5 pids enforced | NOT RUN — pending execution on the target host |
| 6 production flags | NOT RUN — pending execution on the target host |

### Option B (rootful)

| Check | Result |
| --- | --- |
| 1 gVisor | NOT RUN — pending execution on the target host |
| 2 no network | NOT RUN — pending execution on the target host |
| 3 daemon cgroup limits | NOT RUN — pending execution on the target host |
| 4 memory enforced | NOT RUN — pending execution on the target host |
| 5 pids enforced | NOT RUN — pending execution on the target host |
| 6 production flags | NOT RUN — pending execution on the target host |

**Decision: PENDING.**

Decision rule:

1. Run the self-check on option A. If all six pass, decide A.
2. If a check fails under A and passes under B, rootless is the cause. Before falling back to B, try these in A and re-run:
   - add runsc `--systemd-cgroup` to `runtimeArgs` in `~emitkit-sandbox/.config/docker/daemon.json`;
   - make sure `dbus-user-session` is installed (the script's preflight enforces it).
   - Never use `--ignore-cgroups`. It disables the very limits being tested.
3. If a check fails the same way under A and B, the cause is the check or gVisor, not rootless. Fix that first, then decide.
4. Record every attempt in this table and in the decision above.

## Known gaps

- No check has run on a real gVisor host. Tests so far: `bash -n`, shellcheck `-S style`, `--help`, `--dry-run` for both
  modes, self-check's failure paths (unset `DOCKER_HOST`, unreachable socket, fake `docker` branches), and the
  OOM and pids snippets in runc containers (`redis:8.10.3-alpine`, cached). Those runc results are not runsc results.
- `setup-rootless-gvisor.sh` has not been executed (no `runsc`, no sudo on the authoring host).
- `RUNSC_RELEASE` is pinned to `20261026`. The `.sha512` check verifies integrity against the same origin, not provenance.
  Pin a digest before production. `runsc --version` for this release is not observed (see Environment).
- Unverified: whether `runsc` needs the `gvisor-bin/` helpers beside it. The script copies them to `/usr/local/bin/gvisor-bin`,
  which is how the archive lays them out. Check on the target host.
- `--platform=systrap` comes from the plan and is not validated.
- Pids (check 5): the count is of tasks inside the container's own `/proc`. Whether runsc's own threads count toward
  `--pids-limit 256` is UNKNOWN until run. The runc run counted 255 with the limit and 401 without.
- OOM (check 4) uses the 64m limit from the review. The production 1g memory limit is passed in check 6 but not directly exercised.
- Option A assumes `docker-ce-rootless-extras`, `uidmap`, and subuid/subgid ranges. useradd allocates the ranges, and the
  script verifies both files afterwards. It does not install packages.
- Option A: `kernel.apparmor_restrict_unprivileged_userns=1` on the authoring host can block rootlesskit. Record it if it does.
- Mode A delegation is a system-wide drop-in, `/etc/systemd/system/user@.service.d/delegate.conf`. It applies to every
  user manager on the host, and re-running restarts `user@10010`, which stops that user's running containers.
- Mode B: `bridge: none` and `iptables: false` keep the dedicated daemon off `docker0` and the host's DOCKER* iptables
  chains. The daemon has no default bridge network, so a container started without `--network` may fail to start (exit 125;
  reported in review, not reproduced here). Sandbox containers must always be started with an explicit `--network none`
  and never given a network. This is a constraint on SBX-03 and OPS-01.
- Mode B has its own containerd (`/etc/emitkit-sandbox/containerd.toml`, socket under `/run/emitkit-sandbox/containerd`).
  The host's containerd socket, root and the default `moby` namespace are not shared. Its unit is ordered before dockerd.
- Mode B, unverified: the daemon's default network may be missing under `bridge: none`. All self-check containers pass
  `--network none`, so this should not block the checks. Check on the target host.
- The rootless daemon.json and the root-owned files this script manages are rewritten each run. The rootless file is written
  as the sandbox user, and both paths refuse a symlink.
- The toolchain's `/proc` and file-watching behaviour under gVisor (per the v1 design) is not covered by these checks.
- The security-relevant environment for a worker is not yet tested: the worker's own container settings are SBX-03 and OPS-01's job.

## What SBX-03 and OPS-01 should read

- The **Decision** line above. It is PENDING. Do not set `emitkit.sandbox.docker-host` for production until it reads A or B.
- The socket for the chosen option, and its access rule, from the Sockets table.
- Runner limits: `--runtime=runsc --network none --cpus 1 --memory 1g --pids-limit 256`, timeout 180 s (spec 5.6).
- OPS-01 mounts only the sandbox socket directory into the worker, never `/var/run/docker.sock`.

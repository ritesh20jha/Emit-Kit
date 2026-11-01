# SBX-01 spike: gVisor (runsc) with a dedicated sandbox Docker daemon

**Status: PENDING.** Nothing below has been run on the target host. The only observations are the
environment facts in the first table, taken from the authoring host. No check has a result yet.

**Decision: PENDING**

## Environment

Observed on the authoring host (not yet the target host):

| Item | Value |
| --- | --- |
| Kernel | `7.0.0-38-generic` (`uname -r`) |
| Distro | Ubuntu 26.04.1 LTS (`/etc/os-release`) |
| Docker | client 29.9.2; host daemon 29.9.2, cgroup version 2, runtimes: `runc` only |
| cgroup v2 | yes (`cgroup2fs`; controllers: cpuset cpu io memory hugetlb pids rdma misc dmem) |
| runsc | not installed |
| shellcheck | not installed |

## Sockets (`emitkit.sandbox.docker-host`)

| Option | Socket | How to get it |
| --- | --- | --- |
| A, rootless (default) | `unix:///run/user/<uid>/docker.sock` | `<uid>` = `id -u emitkit-sandbox` |
| B, rootful dedicated | `unix:///run/emitkit-sandbox/docker.sock` | fixed path |

## How to run

```sh
# Dry run (prints the commands; changes nothing; no root needed)
deploy/sandbox/setup-rootless-gvisor.sh --dry-run
deploy/sandbox/setup-rootless-gvisor.sh --mode rootful --dry-run

# Option A (rootless)
sudo deploy/sandbox/setup-rootless-gvisor.sh
DOCKER_HOST=unix:///run/user/<uid>/docker.sock bash deploy/sandbox/self-check.sh

# Option B (rootful, only if A fails a check)
sudo deploy/sandbox/setup-rootless-gvisor.sh --mode rootful
DOCKER_HOST=unix:///run/emitkit-sandbox/docker.sock bash deploy/sandbox/self-check.sh
```

## Results

Each check in `deploy/sandbox/self-check.sh` is listed below for both options.

### Option A (rootless)

| Check | Result |
| --- | --- |
| gVisor (`dmesg` names gVisor) | NOT RUN — pending execution on the target host |
| `--network none` blocks network | NOT RUN — pending execution on the target host |
| `--memory 64m` = 67108864 | NOT RUN — pending execution on the target host |
| `--pids-limit 32` enforced | NOT RUN — pending execution on the target host |
| `--cpus 1` accepted | NOT RUN — pending execution on the target host |

### Option B (rootful)

| Check | Result |
| --- | --- |
| gVisor (`dmesg` names gVisor) | NOT RUN — pending execution on the target host |
| `--network none` blocks network | NOT RUN — pending execution on the target host |
| `--memory 64m` = 67108864 | NOT RUN — pending execution on the target host |
| `--pids-limit 32` enforced | NOT RUN — pending execution on the target host |
| `--cpus 1` accepted | NOT RUN — pending execution on the target host |

**Decision: PENDING.** Pick A if every check passes under rootless. Otherwise run B and record it here.

## Known gaps

- No check has run on a real gVisor host. The only tests were `bash -n`, `--help`, `--dry-run` for both modes,
  and self-check's failure paths (unset `DOCKER_HOST`, nonexistent socket). The pass and fail paths of the
  real checks were exercised only against a fake `docker`.
- `setup-rootless-gvisor.sh` has not been executed (no `runsc`, no sudo on the authoring host). shellcheck was not run.
- `RUNSC_RELEASE` defaults to `latest`. The `.sha512` check confirms the download matches its own release
  directory, not who published it. Pin an exact release and expected digest before production.
- Option A assumes `docker-ce-rootless-extras`, `uidmap`, subuid/subgid ranges, and a running systemd user manager.
  The script checks for these but does not install them.
- `--platform=systrap` comes from the plan and is not validated.
- The pids check counts tasks in `/proc`. Its behaviour under gVisor is untested.
- The toolchain's `/proc` and file-watching behaviour under gVisor (per the v1 design) is not covered by these checks.
- Option B: the app user must be in group `emitkit-sandbox` to use the socket.
- The script overwrites the `daemon.json` and systemd drop-in it manages (`~emitkit-sandbox/.config/docker/` for A,
  `/etc/emitkit-sandbox/` for B).

## What SBX-03 and OPS-01 should read

- The **Decision** line above. It is PENDING, so do not set `emitkit.sandbox.docker-host` for production until it reads A or B.
- The socket for the chosen option, from the Sockets table.
- OPS-01 mounts only that socket into the worker, never `/var/run/docker.sock`.

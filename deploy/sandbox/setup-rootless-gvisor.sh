#!/usr/bin/env bash
# Sets up a dedicated gVisor (runsc) Docker daemon for the EmitKit sandbox, kept apart from the host dockerd.
#   rootless (default, option A): rootless dockerd for user emitkit-sandbox (uid/gid 10010),
#                                 socket /run/user/10010/docker.sock
#   rootful  (option B):          rootful dockerd on its own containerd, socket /run/emitkit-sandbox/docker/docker.sock
# Not a no-op on re-run: it restarts user@10010 (A) or the sandbox daemons (B), which stops running sandbox
# containers. Config files it manages are rewritten with the same content each run.
set -euo pipefail

SANDBOX_USER=emitkit-sandbox
SANDBOX_UID=10010
SANDBOX_GID=10010
SANDBOX_HOME=/home/$SANDBOX_USER
RUNSC_BIN=/usr/local/bin/runsc
RUNSC_RELEASE=${RUNSC_RELEASE:-20261026}   # dated gVisor release (newest confirmed); or "latest"
ROOTFUL_SOCK=/run/emitkit-sandbox/docker/docker.sock
CONTAINERD_SOCK=/run/emitkit-sandbox/containerd/containerd.sock
RUNSC_RUNTIME='{"runtimes": {"runsc": {"path": "/usr/local/bin/runsc", "runtimeArgs": ["--platform=systrap"]}}}'
# Mode B: no docker0 and no DOCKER* iptables chains. Sandbox containers must always be started with --network none.
ROOTFUL_DAEMON_JSON='{"bridge": "none", "iptables": false, "runtimes": {"runsc": {"path": "/usr/local/bin/runsc", "runtimeArgs": ["--platform=systrap"]}}}'
DELEGATE='Delegate=cpu cpuset io memory pids'
USER_MANAGER_DROPIN=/etc/systemd/system/user@.service.d/delegate.conf

MODE=rootless
DRY_RUN=0

usage() {
  cat <<EOF
Usage: $0 [--mode rootless|rootful] [--dry-run] [--help]

  --mode rootless  (default) rootless dockerd for user $SANDBOX_USER, uid $SANDBOX_UID (option A)
  --mode rootful   dedicated rootful dockerd and containerd, socket $ROOTFUL_SOCK (option B)
  --dry-run        print the commands that would run; change nothing (no root needed)
  --help           show this help

Environment: RUNSC_RELEASE  dated gVisor release, YYYYMMDD[.N] or latest (default: $RUNSC_RELEASE).
Needs root (run with sudo) unless --dry-run. Re-running restarts the sandbox daemon (see header).
EOF
}

while [ $# -gt 0 ]; do
  case $1 in
    --mode) [ $# -ge 2 ] || { echo "--mode needs a value: rootless or rootful" >&2; exit 2; }; MODE=$2; shift ;;
    --mode=*) MODE=${1#--mode=} ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
case $MODE in rootless|rootful) ;; *) echo "--mode must be rootless or rootful" >&2; exit 2 ;; esac
grep -Eq '^([0-9]{8}(\.[0-9]+)?|latest)$' <<<"$RUNSC_RELEASE" \
  || { echo "RUNSC_RELEASE must be YYYYMMDD, YYYYMMDD.N or latest (got: $RUNSC_RELEASE)" >&2; exit 2; }

die() { echo "setup-rootless-gvisor: $*" >&2; exit 1; }
# run CMD...: runs CMD, or only prints it under --dry-run.
run() { if [ "$DRY_RUN" = 1 ]; then echo "+ $*"; else "$@"; fi; }
# precheck MSG CMD...: CMD must succeed. A failure stops a real run; under --dry-run it is only noted.
precheck() {
  local msg=$1; shift
  "$@" && return 0
  if [ "$DRY_RUN" = 1 ]; then echo "# note: $msg (a real run stops here)"; return 0; fi
  die "$msg"
}
# print_lines PREFIX < text: dry-run display of multi-line content.
print_lines() { local line; while IFS= read -r line; do echo "$1$line"; done; }
# write_file PATH < content: root writes a system file. Refuses to write through a symlink.
write_file() {
  local content; content=$(cat)
  [ ! -L "$1" ] || die "refusing to write through symlink $1"
  if [ "$DRY_RUN" = 1 ]; then echo "+ write $1:"; print_lines "+   " <<<"$content"; return 0; fi
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "$content" >"$1"
}
# write_user_file PATH < content: writes PATH as the sandbox user, so root never follows a user-controlled path.
write_user_file() {
  local content; content=$(cat)
  if [ "$DRY_RUN" = 1 ]; then echo "+ write $1 (as $SANDBOX_USER):"; print_lines "+   " <<<"$content"; return 0; fi
  # shellcheck disable=SC2016  # single quotes on purpose: the script runs in the sandbox user's shell
  printf '%s\n' "$content" | runuser -u "$SANDBOX_USER" -- sh -c '
    chk() { for p in "$@"; do [ ! -L "$p" ] || { echo "refusing: $p is a symlink" >&2; exit 1; }; done; }
    chk "$2" "$(dirname "$1")" "$1"
    mkdir -p "$(dirname "$1")"
    chk "$2" "$(dirname "$1")" "$1"
    cat >"$1"' sh "$1" "$SANDBOX_HOME/.config"
}
# as_sandbox CMD...: runs CMD as the sandbox user, with its systemd user manager reachable.
as_sandbox() { run runuser -u "$SANDBOX_USER" -- env HOME="$SANDBOX_HOME" XDG_RUNTIME_DIR="/run/user/$SANDBOX_UID" "$@"; }

preflight() {
  [ "$DRY_RUN" = 1 ] || [ "$(id -u)" -eq 0 ] || die "must run as root (try: sudo $0 --mode $MODE)"
  precheck "cgroup v2 (unified hierarchy) is required" test "$(stat -fc %T /sys/fs/cgroup)" = cgroup2fs
  precheck "systemctl not found (systemd is required)" command -v systemctl >/dev/null
  precheck "curl not found (needed to download runsc)" command -v curl >/dev/null
  precheck "sha512sum not found (coreutils)" command -v sha512sum >/dev/null
  precheck "tar and bzip2 are needed to unpack the gVisor archive" sh -c 'command -v tar && command -v bzip2' >/dev/null
  precheck "runuser not found (util-linux)" command -v runuser >/dev/null
  if [ "$MODE" = rootless ]; then
    precheck "dockerd-rootless-setuptool.sh not found (install docker-ce-rootless-extras)" command -v dockerd-rootless-setuptool.sh >/dev/null
    precheck "newuidmap not found (install uidmap)" command -v newuidmap >/dev/null
    precheck "/usr/lib/systemd/user/dbus.socket missing (install dbus-user-session)" test -e /usr/lib/systemd/user/dbus.socket
    if [ "$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns 2>/dev/null || echo 0)" = 1 ]; then
      echo "# warning: kernel.apparmor_restrict_unprivileged_userns=1; rootlesskit may fail to create user namespaces." >&2
    fi
  else
    precheck "dockerd not found (install docker-ce)" command -v dockerd >/dev/null
    precheck "containerd not found (install containerd.io; mode B runs its own containerd)" command -v containerd >/dev/null
  fi
}

# install_runsc: download the pinned gVisor archive and its .sha512, verify, install runsc and the shim.
# Integrity only: the checksum comes from the same origin as the archive, so it does not prove who published it.
install_runsc() {
  local arch base
  arch=$(uname -m)
  base=https://storage.googleapis.com/gvisor/releases/release/$RUNSC_RELEASE/$arch
  run curl -fsSL -o "$TMP/gvisor.tar.bz2" "$base/gvisor.tar.bz2"
  run curl -fsSL -o "$TMP/gvisor.tar.bz2.sha512" "$base/gvisor.tar.bz2.sha512"
  if [ "$DRY_RUN" = 1 ]; then echo "+ (cd $TMP && sha512sum -c gvisor.tar.bz2.sha512)"
  else (cd "$TMP" && sha512sum -c gvisor.tar.bz2.sha512) || die "gVisor checksum verification failed"; fi
  run tar -xjf "$TMP/gvisor.tar.bz2" -C "$TMP"
  run install -m 0755 "$TMP/runsc" "$RUNSC_BIN"
  run install -m 0755 "$TMP/containerd-shim-runsc-v1" /usr/local/bin/containerd-shim-runsc-v1
  run cp -a "$TMP/gvisor-bin" /usr/local/bin/
  run "$RUNSC_BIN" --version
}

# Fixed uid/gid so OPS-01 can hard-code them. useradd allocates subuid/subgid itself (SUB_UID_COUNT in login.defs).
ensure_group() {
  getent group "$SANDBOX_USER" >/dev/null && return 0
  precheck "gid $SANDBOX_GID is already used by another group" test -z "$(getent group "$SANDBOX_GID")"
  run groupadd --gid "$SANDBOX_GID" "$SANDBOX_USER"
}
ensure_user() {
  if id "$SANDBOX_USER" >/dev/null 2>&1; then
    [ "$(id -u "$SANDBOX_USER")" = "$SANDBOX_UID" ] || die "$SANDBOX_USER exists with uid $(id -u "$SANDBOX_USER"), expected $SANDBOX_UID"
    return 0
  fi
  precheck "uid $SANDBOX_UID is already used by another account" test -z "$(getent passwd "$SANDBOX_UID")"
  run useradd --uid "$SANDBOX_UID" --gid "$SANDBOX_GID" --create-home --shell /usr/sbin/nologin "$SANDBOX_USER"
}
verify_subids() {
  if [ "$DRY_RUN" = 1 ]; then echo "# subuid/subgid: must exist for $SANDBOX_USER (allocated by useradd); verified after setup"; return 0; fi
  if ! { grep -q "^$SANDBOX_USER:" /etc/subuid && grep -q "^$SANDBOX_USER:" /etc/subgid; }; then
    die "no subuid/subgid range for $SANDBOX_USER; add a free range (usermod --add-subuids/--add-subgids) and re-run"
  fi
}

setup_rootless() {
  ensure_group
  ensure_user
  verify_subids
  run loginctl enable-linger "$SANDBOX_USER"
  install_runsc
  # Delegation must reach the user manager itself: a system-wide drop-in for user@.service, then restart the
  # manager before dockerd starts. A drop-in on the user's own docker.service cannot widen it.
  write_file "$USER_MANAGER_DROPIN" <<EOF
[Service]
$DELEGATE
EOF
  run systemctl daemon-reload
  run systemctl restart "user@$SANDBOX_UID.service"
  write_user_file "$SANDBOX_HOME/.config/docker/daemon.json" <<<"$RUNSC_RUNTIME"
  if [ ! -f "$SANDBOX_HOME/.config/systemd/user/docker.service" ]; then
    as_sandbox dockerd-rootless-setuptool.sh install
  fi
  as_sandbox systemctl --user daemon-reload
  as_sandbox systemctl --user enable docker.service
  as_sandbox systemctl --user restart docker.service
}

setup_rootful() {
  ensure_group
  install_runsc
  write_file /etc/emitkit-sandbox/containerd.toml <<EOF
version = 2
root = "/var/lib/emitkit-sandbox/containerd"
state = "/run/emitkit-sandbox/containerd/state"

[grpc]
  address = "$CONTAINERD_SOCK"
EOF
  write_file /etc/emitkit-sandbox/daemon.json <<<"$ROOTFUL_DAEMON_JSON"
  # Dedicated containerd: the host's containerd socket, root and the default "moby" namespace are never shared.
  write_file /etc/systemd/system/emitkit-sandbox-containerd.service <<EOF
[Unit]
Description=EmitKit sandbox containerd (dedicated, not the host's)
After=network.target

[Service]
Type=notify
ExecStart=/usr/bin/containerd --config /etc/emitkit-sandbox/containerd.toml
RuntimeDirectory=emitkit-sandbox/containerd
RuntimeDirectoryPreserve=yes
$DELEGATE
KillMode=process
LimitNOFILE=infinity
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
  write_file /etc/systemd/system/emitkit-sandbox-docker.service <<EOF
[Unit]
Description=EmitKit sandbox Docker daemon (gVisor, dedicated)
After=network-online.target emitkit-sandbox-containerd.service
Requires=emitkit-sandbox-containerd.service

[Service]
Type=notify
ExecStart=/usr/bin/dockerd --host=unix://$ROOTFUL_SOCK --group=$SANDBOX_USER --config-file=/etc/emitkit-sandbox/daemon.json --containerd=$CONTAINERD_SOCK --containerd-namespace=emitkit-sandbox --containerd-plugins-namespace=emitkit-sandbox-plugins --data-root=/var/lib/emitkit-sandbox/docker --exec-root=/run/emitkit-sandbox/docker/exec --pidfile=/run/emitkit-sandbox/docker/docker.pid
RuntimeDirectory=emitkit-sandbox/docker
RuntimeDirectoryPreserve=yes
$DELEGATE
KillMode=process
LimitNOFILE=infinity
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
  run systemctl daemon-reload
  run systemctl enable emitkit-sandbox-containerd.service emitkit-sandbox-docker.service
  run systemctl restart emitkit-sandbox-containerd.service emitkit-sandbox-docker.service
}

preflight
TMP=/tmp/emitkit-gvisor-dry-run   # placeholder under --dry-run only; a real run uses mktemp
if [ "$DRY_RUN" != 1 ]; then TMP=$(mktemp -d); fi
trap '[ "$DRY_RUN" = 1 ] || rm -rf "$TMP"' EXIT

if [ "$MODE" = rootless ]; then
  setup_rootless
  host="unix:///run/user/$SANDBOX_UID/docker.sock"
  echo "worker: run as uid $SANDBOX_UID:$SANDBOX_GID (/run/user/$SANDBOX_UID is 0700, group membership is not enough)"
else
  setup_rootful
  host="unix://$ROOTFUL_SOCK"
  echo "worker: group_add [\"$SANDBOX_GID\"]; mount the directory /run/emitkit-sandbox/docker, not the socket file"
fi
echo "Verify: sudo env DOCKER_HOST=$host bash deploy/sandbox/self-check.sh"
echo "DOCKER_HOST=$host"

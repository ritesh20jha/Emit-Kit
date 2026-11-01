#!/usr/bin/env bash
# Sets up a dedicated gVisor (runsc) Docker daemon for the EmitKit sandbox, kept apart from the host dockerd.
#   rootless (default, option A): rootless dockerd run by user emitkit-sandbox, socket /run/user/<uid>/docker.sock
#   rootful  (option B):          rootful dockerd on unix:///run/emitkit-sandbox/docker.sock
# Idempotent: re-running converges. Overwrites the daemon.json and systemd drop-in files it manages.
set -euo pipefail

SANDBOX_USER=emitkit-sandbox
SANDBOX_HOME=/home/$SANDBOX_USER
RUNSC_BIN=/usr/local/bin/runsc
RUNSC_RELEASE=${RUNSC_RELEASE:-latest}   # pin an exact release in production
ROOTFUL_SOCK=/run/emitkit-sandbox/docker.sock
RUNSC_RUNTIME='{"runtimes": {"runsc": {"path": "/usr/local/bin/runsc", "runtimeArgs": ["--platform=systrap"]}}}'
DELEGATE='Delegate=cpu cpuset io memory pids'

MODE=rootless
DRY_RUN=0

usage() {
  cat <<EOF
Usage: $0 [--mode rootless|rootful] [--dry-run] [--help]

  --mode rootless  (default) rootless dockerd for user $SANDBOX_USER (option A)
  --mode rootful   dedicated rootful dockerd on $ROOTFUL_SOCK (option B)
  --dry-run        print the commands that would run; change nothing (no root needed)
  --help           show this help

Environment: RUNSC_RELEASE  gVisor release to install (default: latest).
Needs root (run with sudo) unless --dry-run.
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

die() { echo "setup-rootless-gvisor: $*" >&2; exit 1; }
# run CMD...: runs CMD, or only prints it under --dry-run.
run() { if [ "$DRY_RUN" = 1 ]; then echo "+ $*"; else "$@"; fi; }
# need CMD HINT: CMD must exist. Under --dry-run this only warns, so the plan shows on any host.
need() {
  command -v "$1" >/dev/null 2>&1 && return 0
  if [ "$DRY_RUN" = 1 ]; then echo "# note: $1 not found on this host; a real run stops here ($2)"; return 0; fi
  die "$1 not found ($2)"
}
# write_file PATH < content: writes stdin to PATH (under --dry-run it prints the content instead).
write_file() {
  local content; content=$(cat)
  if [ "$DRY_RUN" = 1 ]; then echo "+ write $1:"; sed 's/^/+   /' <<<"$content"; return 0; fi
  mkdir -p "$(dirname "$1")"
  printf '%s\n' "$content" >"$1"
}
sandbox_uid() { id -u "$SANDBOX_USER" 2>/dev/null || echo '<uid>'; }
# as_sandbox CMD...: runs CMD as the sandbox user, with its systemd user manager reachable.
as_sandbox() { run runuser -u "$SANDBOX_USER" -- env HOME="$SANDBOX_HOME" XDG_RUNTIME_DIR="/run/user/$(sandbox_uid)" "$@"; }

preflight() {
  [ "$DRY_RUN" = 1 ] || [ "$(id -u)" -eq 0 ] || die "must run as root (try: sudo $0 --mode $MODE)"
  [ "$(stat -fc %T /sys/fs/cgroup)" = cgroup2fs ] || die "cgroup v2 (unified hierarchy) is required"
  need systemctl "systemd is required"
  need curl "needed to download runsc"
  need sha512sum "coreutils"
  if [ "$MODE" = rootless ]; then
    need dockerd-rootless-setuptool.sh "install docker-ce-rootless-extras"
    need newuidmap "install uidmap"
  else
    need dockerd "install docker-ce"
  fi
}

# install_runsc: download runsc and its .sha512 for this arch, verify, install to $RUNSC_BIN.
install_runsc() {
  local base=https://storage.googleapis.com/gvisor/releases/release/$RUNSC_RELEASE/$(uname -m)
  run curl -fsSL -o "$TMP/runsc" "$base/runsc"
  run curl -fsSL -o "$TMP/runsc.sha512" "$base/runsc.sha512"
  if [ "$DRY_RUN" = 1 ]; then echo "+ (cd $TMP && sha512sum -c runsc.sha512)"
  else (cd "$TMP" && sha512sum -c runsc.sha512) || die "runsc checksum verification failed"; fi
  run install -m 0755 "$TMP/runsc" "$RUNSC_BIN"
  run "$RUNSC_BIN" --version
}

setup_rootless() {
  id "$SANDBOX_USER" >/dev/null 2>&1 || run useradd --create-home --shell /usr/sbin/nologin "$SANDBOX_USER"
  grep -q "^$SANDBOX_USER:" /etc/subuid || run usermod --add-subuids 100000-165535 --add-subgids 100000-165535 "$SANDBOX_USER"
  run loginctl enable-linger "$SANDBOX_USER"
  run systemctl start "user@$(sandbox_uid).service"
  install_runsc
  write_file "$SANDBOX_HOME/.config/docker/daemon.json" <<<"$RUNSC_RUNTIME"
  write_file "$SANDBOX_HOME/.config/systemd/user/docker.service.d/delegate.conf" <<EOF
[Service]
$DELEGATE
EOF
  run chown -R "$SANDBOX_USER:$SANDBOX_USER" "$SANDBOX_HOME/.config"
  if [ ! -f "$SANDBOX_HOME/.config/systemd/user/docker.service" ]; then
    as_sandbox dockerd-rootless-setuptool.sh install
  fi
  as_sandbox systemctl --user daemon-reload
  as_sandbox systemctl --user enable docker.service
  as_sandbox systemctl --user restart docker.service
}

setup_rootful() {
  getent group "$SANDBOX_USER" >/dev/null || run groupadd --system "$SANDBOX_USER"
  install_runsc
  write_file /etc/emitkit-sandbox/daemon.json <<<"$RUNSC_RUNTIME"
  write_file /etc/systemd/system/emitkit-sandbox-docker.service <<EOF
[Unit]
Description=EmitKit sandbox Docker daemon (gVisor)
After=network-online.target

[Service]
Type=notify
ExecStart=/usr/bin/dockerd --host=unix://$ROOTFUL_SOCK --group=$SANDBOX_USER --config-file=/etc/emitkit-sandbox/daemon.json --data-root=/var/lib/emitkit-sandbox --exec-root=/run/emitkit-sandbox/exec --pidfile=/run/emitkit-sandbox/docker.pid
RuntimeDirectory=emitkit-sandbox
$DELEGATE
KillMode=process
LimitNOFILE=infinity
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
  run systemctl daemon-reload
  run systemctl enable emitkit-sandbox-docker.service
  run systemctl restart emitkit-sandbox-docker.service
}

preflight
TMP=/tmp/emitkit-runsc-dry-run   # placeholder path under --dry-run; a real run uses mktemp
if [ "$DRY_RUN" != 1 ]; then TMP=$(mktemp -d); fi
trap '[ "$DRY_RUN" = 1 ] || rm -rf "$TMP"' EXIT

if [ "$MODE" = rootless ]; then setup_rootless; host="unix:///run/user/$(sandbox_uid)/docker.sock"; else setup_rootful; host="unix://$ROOTFUL_SOCK"; fi
echo "Verify: DOCKER_HOST=$host bash deploy/sandbox/self-check.sh"
echo "DOCKER_HOST=$host"

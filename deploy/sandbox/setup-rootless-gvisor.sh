#!/usr/bin/env bash
# Sets up a dedicated sandbox Docker daemon with gVisor (runsc), separate from the host daemon (spec 5.6).
#   sudo bash deploy/sandbox/setup-rootless-gvisor.sh --mode rootless|rootful
#   rootless (option A): dockerd runs as user emitkit-sandbox; socket unix:///run/user/10010/docker.sock.
#   rootful  (option B): systemd-managed dockerd; socket unix:///run/emitkit-sandbox/docker.sock.
# NOT EXECUTED in the spike (no root there). See docs/spikes/2026-gvisor-rootless.md.
set -euo pipefail

MODE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift 2 ;;
    *) MODE="" ; break ;;
  esac
done
case "$MODE" in
  rootless|rootful) ;;
  *) echo "usage: $0 --mode rootless|rootful" >&2; exit 2 ;;
esac
[ "$(id -u)" -eq 0 ] || { echo "must run as root" >&2; exit 1; }

RUNSC_SRC=${RUNSC_SRC:-/usr/bin/runsc}
DAEMON_JSON='{"runtimes":{"runsc":{"path":"/usr/local/bin/runsc","runtimeArgs":["--platform=systrap"]}}}'
UID_SANDBOX=10010

id -u emitkit-sandbox >/dev/null 2>&1 || useradd --uid "$UID_SANDBOX" --create-home --shell /usr/sbin/nologin emitkit-sandbox
install -m 0755 "$RUNSC_SRC" /usr/local/bin/runsc
/usr/local/bin/runsc --version

if [ "$MODE" = rootless ]; then
  grep -q '^emitkit-sandbox:' /etc/subuid || usermod --add-subuids 100000-165535 --add-subgids 100000-165535 emitkit-sandbox
  systemctl set-property "user@$UID_SANDBOX.service" Delegate=cpu cpuset io memory pids
  loginctl enable-linger emitkit-sandbox
  systemctl start "user@$UID_SANDBOX.service"
  SBX_ENV=(env HOME=/home/emitkit-sandbox XDG_RUNTIME_DIR="/run/user/$UID_SANDBOX")
  runuser -u emitkit-sandbox -- "${SBX_ENV[@]}" dockerd-rootless-setuptool.sh install
  install -d -o emitkit-sandbox -g emitkit-sandbox /home/emitkit-sandbox/.config/docker
  printf '%s\n' "$DAEMON_JSON" > /home/emitkit-sandbox/.config/docker/daemon.json
  chown emitkit-sandbox:emitkit-sandbox /home/emitkit-sandbox/.config/docker/daemon.json
  runuser -u emitkit-sandbox -- "${SBX_ENV[@]}" systemctl --user restart docker
  echo "docker-host: unix:///run/user/$UID_SANDBOX/docker.sock"
else
  install -d -m 0750 /etc/emitkit-sandbox
  printf '%s\n' "$DAEMON_JSON" > /etc/emitkit-sandbox/daemon.json
  cat > /etc/systemd/system/emitkit-sandbox-docker.service <<'UNIT'
[Unit]
Description=EmitKit sandbox Docker daemon (gVisor, dedicated)

[Service]
RuntimeDirectory=emitkit-sandbox
ExecStart=/usr/bin/dockerd --host unix:///run/emitkit-sandbox/docker.sock --group emitkit-sandbox --data-root /var/lib/emitkit-sandbox/docker --exec-root /run/emitkit-sandbox/exec --pidfile /run/emitkit-sandbox/docker.pid --config-file /etc/emitkit-sandbox/daemon.json --bridge none
Delegate=yes
KillMode=process
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable --now emitkit-sandbox-docker.service
  echo "docker-host: unix:///run/emitkit-sandbox/docker.sock"
fi

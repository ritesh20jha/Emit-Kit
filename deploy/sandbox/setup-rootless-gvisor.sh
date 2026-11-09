#!/usr/bin/env bash
# Sets up a dedicated sandbox Docker daemon with gVisor (runsc), separate from the host daemon (spec 5.6).
#   sudo bash deploy/sandbox/setup-rootless-gvisor.sh --mode rootful
#   sudo bash deploy/sandbox/setup-rootless-gvisor.sh --mode rootless --i-know-runsc-fails-rootless
#   rootful  (option B, the spike decision): systemd-managed dockerd; socket unix:///run/emitkit-sandbox/docker.sock.
#   rootless (option A, RULED OUT): dockerd runs as user emitkit-sandbox; socket unix:///run/user/10010/docker.sock.
#     WARNING: the spike showed runsc cannot create its sandbox cgroup under rootless Docker on this host
#     (docs/spikes/2026-gvisor-rootless.md). Kept only for diagnosis; it is refused without the flag.
# NOT EXECUTED in the spike (no root there).
set -euo pipefail

usage() { echo "usage: $0 --mode rootful | --mode rootless --i-know-runsc-fails-rootless" >&2; exit 2; }
MODE=""
ACK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --mode) [ $# -ge 2 ] || usage; MODE="$2"; shift 2 ;;
    --i-know-runsc-fails-rootless) ACK=1; shift ;;
    *) usage ;;
  esac
done
case "$MODE" in
  rootful) ;;
  rootless)
    [ -n "$ACK" ] || {
      echo "REFUSING --mode rootless: option A is ruled out by the SBX-01 spike." >&2
      echo "  runsc cannot create its sandbox cgroup under rootless Docker on this host." >&2
      echo "  Use --mode rootful (decision B). To run the rootless probe anyway, add --i-know-runsc-fails-rootless." >&2
      exit 2
    }
    echo "WARNING: --mode rootless is known not to run runsc containers with cgroup limits." >&2 ;;
  *) usage ;;
esac
[ "$(id -u)" -eq 0 ] || { echo "must run as root" >&2; exit 1; }

RUNSC_SRC=${RUNSC_SRC:-/usr/bin/runsc}
# SECURITY: under --mode rootful, whoever can use the socket (group emitkit-sandbox) has root on the host.
# default-runtime runsc is defence in depth only: a client can still ask for --runtime=runc.
# See "Security under B" in docs/spikes/2026-gvisor-rootless.md. Who joins the group is an OPS-01 decision.
DAEMON_JSON='{"default-runtime":"runsc","runtimes":{"runsc":{"path":"/usr/local/bin/runsc","runtimeArgs":["--platform=systrap"]}}}'
UID_SANDBOX=10010

id -u emitkit-sandbox >/dev/null 2>&1 || useradd --uid "$UID_SANDBOX" --create-home --shell /usr/sbin/nologin emitkit-sandbox
if [ "$(readlink -f "$RUNSC_SRC")" = /usr/local/bin/runsc ]; then
  echo "RUNSC_SRC is already /usr/local/bin/runsc; skipping copy" >&2
else
  install -m 0755 "$RUNSC_SRC" /usr/local/bin/runsc
fi
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
ExecStart=/usr/bin/dockerd --host unix:///run/emitkit-sandbox/docker.sock --group emitkit-sandbox --data-root /var/lib/emitkit-sandbox/docker --exec-root /run/emitkit-sandbox/exec --pidfile /run/emitkit-sandbox/docker.pid --config-file /etc/emitkit-sandbox/daemon.json --bridge none --iptables=false --ip6tables=false --containerd-namespace emitkit-sandbox --containerd-plugins-namespace emitkit-sandbox-plugins
Delegate=yes
KillMode=process
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable --now emitkit-sandbox-docker.service
  # The unit is Type=simple, so "enable --now" returns before dockerd listens. Wait for it.
  SOCK=unix:///run/emitkit-sandbox/docker.sock
  for _ in $(seq 30); do docker -H "$SOCK" info >/dev/null 2>&1 && break; sleep 1; done
  docker -H "$SOCK" info >/dev/null || { echo "dedicated daemon not ready at $SOCK" >&2; exit 1; }
  echo "docker-host: $SOCK"
fi

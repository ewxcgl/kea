#!/usr/bin/env bash
#
# KEA relay — Oracle Cloud Always Free bootstrap.
#
# Run ONCE, as a normal user, on a fresh Ubuntu 24.04 ARM instance:
#
#     bash bootstrap-oracle.sh
#
# It is idempotent: re-running repairs a broken install without touching
# configuration. It installs into /opt/kea and runs the relay as an unprivileged
# user behind a locked-down systemd unit.
#
# It does NOT open any firewall port. TLS is a separate step (tls-setup.sh),
# because whether you want a Cloudflare tunnel or a public 443 depends on
# whether you own a domain.

set -euo pipefail

APP_DIR=/opt/kea
APP_USER=kea
VENV="$APP_DIR/venv"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -ne 0 ]] || die "Run as a normal user; it will sudo where it needs to."

log "Installing packages"
sudo apt-get update -qq
sudo apt-get install -y -qq python3-venv ca-certificates curl

[[ -f "$SRC/relay.py" ]] || die "relay.py not found next to this script (looked in $SRC)"

log "Creating $APP_USER and $APP_DIR"
if ! id -u "$APP_USER" >/dev/null 2>&1; then
  sudo useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
fi
sudo install -d -o "$APP_USER" -g "$APP_USER" -m 750 "$APP_DIR"

log "Copying relay"
sudo install -o "$APP_USER" -g "$APP_USER" -m 640 "$SRC/relay.py" "$APP_DIR/relay.py"
sudo install -o "$APP_USER" -g "$APP_USER" -m 640 "$SRC/requirements.txt" "$APP_DIR/requirements.txt"

log "Building virtualenv (runtime deps only)"
if [[ ! -x "$VENV/bin/python" ]]; then
  sudo python3 -m venv "$VENV"
fi
sudo "$VENV/bin/pip" install --quiet --upgrade pip
sudo "$VENV/bin/pip" install --quiet -r "$APP_DIR/requirements.txt"

log "Writing hardened systemd unit"
sudo tee /etc/systemd/system/kea-relay.service >/dev/null <<'UNIT'
[Unit]
Description=KEA relay (content-blind location fanout)
Documentation=https://github.com/your-org/kea
After=network-online.target
Wants=network-online.target

[Service]
Type=exec
User=kea
Group=kea
Environment=PORT=8080
# Loopback only. Nothing outside this machine can reach the relay directly;
# TLS termination is a separate process (see tls-setup.sh).
Environment=HOST=127.0.0.1
Environment=LOG_LEVEL=INFO
ExecStart=/opt/kea/venv/bin/python /opt/kea/relay.py
Restart=always
RestartSec=5
StartLimitBurst=5

# --- hardening -------------------------------------------------------------
# The relay needs to read its own directory, open one listening socket, and
# write nothing. Everything else is refused by the kernel rather than trusted.
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
ProtectProc=invisible
RestrictSUIDSGID=true
RestrictRealtime=true
RestrictNamespaces=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
LockPersonality=true
MemoryDenyWriteExecute=true
SystemCallArchitectures=native
SystemCallFilter=@system-service
SystemCallErrorNumber=EPERM
CapabilityBoundingSet=
AmbientCapabilities=

# It uses ~40 MB. 256 MB is generous; exceeding it is a bug, not a workload.
MemoryMax=256M
MemoryHigh=192M
CPUQuota=50%
TasksMax=64

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now kea-relay.service
sleep 2

log "Checking health"
if curl -fsS http://127.0.0.1:8080/health; then
  echo
else
  warn "Relay did not answer /health. Recent log:"
  sudo journalctl -u kea-relay -n 30 --no-pager
  exit 1
fi

log "Done — relay is running and bound to 127.0.0.1 only"
cat <<'NEXT'

  Confirm no inbound port is needed:
      sudo iptables -S | grep -c 8080     # expect 0 matches

  Next step — TLS (pick one):
      bash tls-setup.sh cloudflare    # stable URL, ZERO inbound ports (needs a domain)
      bash tls-setup.sh duckdns      # fully free subdomain, needs 80+443 open

  Then paste the resulting wss:// URL into KEA -> Settings -> Relay server.
NEXT
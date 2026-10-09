#!/usr/bin/env bash
#
# TLS for the KEA relay. Pick a backend:
#
#     bash tls-setup.sh cloudflare   # stable URL, ZERO inbound ports (needs a domain)
#     bash tls-setup.sh duckdns      # free subdomain, needs ports 80+443 open
#
# Both terminate TLS outside the relay. The relay itself keeps speaking plain
# HTTP to 127.0.0.1 and never sees a key or a certificate, which is the same
# property that keeps it from ever seeing a coordinate.
#
# WebSockets need no extra configuration: Caddy and the Cloudflare tunnel both
# proxy Upgrade transparently.

set -euo pipefail

MODE="${1:-}"
APP_DIR=/opt/kea

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -ne 0 ]] || die "Run as a normal user."
systemctl is-active --quiet kea-relay || die "kea-relay is not running; run bootstrap-oracle.sh first."

# ===========================================================================
case "$MODE" in
cloudflare)
  # -----------------------------------------------------------------------
  # A *named* tunnel gives a permanent hostname. A quick tunnel would give a
  # random trycloudflare.com URL that changes on every restart, which would
  # silently break the URL baked into the app — so it is not offered here.
  #
  # Requires: a domain you own, added to a free Cloudflare account, with
  # Cloudflare's nameservers. That is the price of having zero inbound ports.

  command -v cloudflared >/dev/null || {
    log "Installing cloudflared"
    curl -fsSL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64 \
      -o /tmp/cloudflared || curl -fsSL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o /tmp/cloudflared
    sudo install -m 0755 /tmp/cloudflared /usr/local/bin/cloudflared
  }

  log "Authorising this machine with Cloudflare (opens a browser)"
  cloudflared tunnel login

  TUNNEL="${TUNNEL:-kea-relay}"
  log "Creating the named tunnel '$TUNNEL'"
  cloudflared tunnel create "$TUNNEL" 2>/dev/null || warn "tunnel already exists, reusing it"

  TUNNEL_ID="$(cloudflared tunnel list --output json | python3 -c "import json,sys;print(next(t['id'] for t in json.load(sys.stdin) if t['name']=='$TUNNEL'))")"

  HOSTNAME="${HOSTNAME:-relay.$(cat "$HOME/.cloudflared/${TUNNEL}.yml" >/dev/null 2>&1 && echo example.com || echo example.com)}"
  read -rp "Public hostname for the tunnel (e.g. relay.example.com): " HOSTNAME

  log "Writing tunnel config"
  mkdir -p "$HOME/.cloudflared"
  sudo tee /etc/cloudflared/config.yml >/dev/null <<YAML
tunnel: ${TUNNEL_ID}
credentials-file: /etc/cloudflared/${TUNNEL_ID}.json
ingress:
  - hostname: ${HOSTNAME}
    service: http://127.0.0.1:8080
  - service: http_status:404
YAML

  sudo install -m 0600 "$HOME/.cloudflared/${TUNNEL_ID}.json" /etc/cloudflared/
  sudo chown -R root:root /etc/cloudflared
  sudo install -m 0755 /dev/null /etc/systemd/system/kea-tunnel.service
  sudo tee /etc/systemd/system/kea-tunnel.service >/dev/null <<UNIT
[Unit]
Description=Cloudflare tunnel for the KEA relay
After=network-online.target kea-relay.service
Wants=network-online.target
Requires=kea-relay.service

[Service]
Type=exec
ExecStart=/usr/local/bin/cloudflared --no-autoupdate tunnel run
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=false
ProtectKernelTunables=true
ProtectKernelModules=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
CapabilityBoundingSet=
AmbientCapabilities=

[Install]
WantedBy=multi-user.target
UNIT

  sudo systemctl daemon-reload
  sudo systemctl enable --now kea-tunnel.service

  cat <<NEXT

  The tunnel is up, but it is not public until DNS points at it.
  Cloudflare Zero Trust -> Networks -> Tunnels -> $TUNNEL -> Add route
      hostname : $HOSTNAME
      service  : http://127.0.0.1:8080

  Then set in the app:
      wss://${HOSTNAME}/ws

  You may now leave every inbound port closed. Confirm:
      sudo ss -tlnp | grep -v 127.0.0.1
NEXT
  ;;

duckdns)
  # -----------------------------------------------------------------------
  # Fully free and needs no domain purchase, but ports 80 and 443 must be open
  # in the Oracle security list, because Caddy answers the ACME challenge
  # directly.

  SUBDOMAIN="${SUBDOMAIN:-}"
  [[ -n "$SUBDOMAIN" ]] || read -rp "DuckDNS subdomain (e.g. mykea): " SUBDOMAIN
  TOKEN="${DUCKDNS_TOKEN:-}"
  [[ -n "$TOKEN" ]] || read -rsp "DuckDNS API token: " TOKEN; echo

  HOST="relay.${SUBDOMAIN}.duckdns.org"
  PUBLIC_IP="$(curl -fsS https://api.ipify.org)"

  log "Pointing $HOST at $PUBLIC_IP"
  curl -fsS "https://www.duckdns.org/update?domains=${SUBDOMAIN}&token=${TOKEN}&ip=${PUBLIC_IP}" >/dev/null

  log "Installing Caddy"
  sudo apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl
  curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/gpg.key" \
    | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf "https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt" \
    | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  sudo apt-get update -qq && sudo apt-get install -y -qq caddy

  log "Writing Caddyfile"
  sudo tee /etc/caddy/Caddyfile >/dev/null <<CADDY
${HOST} {
    reverse_proxy 127.0.0.1:8080
    encode zstd gzip

    header {
        Strict-Transport-Security "max-age=31536000"
        X-Content-Type-Options "nosniff"
        # The relay returns JSON only; nothing here should ever be framed.
        X-Frame-Options "DENY"
        Referrer-Policy "no-referrer"
        # Do not let a browser or proxy cache location traffic.
        Cache-Control "no-store"
    }

    log {
        output file /var/log/caddy/kea-relay.log
        format json
    }
}
CADDY

  sudo systemctl reload caddy || sudo systemctl restart caddy

  log "Keeping DuckDNS pointed here after an instance rebuild"
  sudo tee /usr/local/bin/kea-duckdns-update >/dev/null <<'SH'
#!/bin/sh
# Re-publish this instance's address to DuckDNS.
set -eu
ip="$(curl -fsS https://api.ipify.org)"
curl -fsS "https://www.duckdns.org/update?domains=${DUCKDNS_SUBDOMAIN}&token=${DUCKDNS_TOKEN}&ip=${ip}" >/dev/null
SH
  sudo sed -i "s/\${DUCKDNS_SUBDOMAIN}/${SUBDOMAIN}/; s/\${DUCKDNS_TOKEN}/${TOKEN}/" /usr/local/bin/kea-duckdns-update
  sudo chmod 0755 /usr/local/bin/kea-duckdns-update

  if ! crontab -l 2>/dev/null | grep -q kea-duckdns-update; then
    ( crontab -l 2>/dev/null; echo "*/15 * * * * /usr/local/bin/kea-duckdns-update >/dev/null 2>&1" ) | crontab -
  fi

  cat <<NEXT

  Open ports 80 and 443 in the Oracle security list (TCP, source 0.0.0.0/0).
  Caddy will obtain a Let's Encrypt certificate on first request.

  Wait ~30s, then verify:
      curl https://${HOST}/health

  Then set in the app:
      wss://${HOST}/ws

  Note: Caddy will not get a certificate until DNS has propagated and the
  security list is open. Watch it with:
      sudo journalctl -u caddy -f
NEXT
  ;;

*)
  die "usage: bash tls-setup.sh {cloudflare|duckdns}"
  ;;
esac
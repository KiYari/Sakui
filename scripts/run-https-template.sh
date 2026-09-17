#!/bin/sh
# Copied into the release folder as run-https.sh by build-release.sh.
# Starts eeck bound to 127.0.0.1 only, and Caddy in front of it on ports
# 80/443, terminating TLS with an automatic Let's Encrypt certificate.
#
# For a real server, run this under systemd instead of by hand - see
# eeck.service.template and eeck-caddy.service.template next to this script.
#
# Requires:
#   - This domain's DNS A record already pointing at this machine's public
#     IP (Let's Encrypt has to reach this machine to verify it).
#   - Permission to bind ports 80/443: run as root, or grant the binary
#     the capability once so nothing here needs root at all:
#       sudo setcap 'cap_net_bind_service=+ep' ./caddy
#   - Ports 80 and 443 open inbound (firewall / cloud security group) -
#     Caddy needs both, even for an HTTPS-only site: port 80 answers the
#     ACME challenge and redirects to 443.
#
# Usage:  ./run-https.sh your-domain.example.com
set -eu
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 your-domain.example.com" >&2
    exit 1
fi

cd "$(dirname "$0")"

EECK_DOMAIN="$1"
export EECK_DOMAIN
export EECK_HOST=127.0.0.1
export EECK_PORT=3001
export EECK_WEB_DIST=web-dist
# Caddy connects from 127.0.0.1 itself; without this every client would
# look like Caddy to the per-client rate limits and share one budget.
# Caddy's reverse_proxy sets X-Forwarded-For to the real client itself.
export EECK_TRUST_PROXY=true

chmod +x server/bin/server ./caddy 2>/dev/null || true

echo "Starting eeck on 127.0.0.1:$EECK_PORT (not reachable except through Caddy) ..."
./server/bin/server &
EECK_SERVER_PID=$!
# Stop the server together with Caddy, whichever way this script ends.
trap 'kill "$EECK_SERVER_PID" 2>/dev/null || true' EXIT INT TERM

sleep 2

echo
echo "Starting Caddy for $EECK_DOMAIN on ports 80/443 ..."
echo "Caddy's own logs (including certificate issuance) print below."
echo
# Not exec'd: the trap above (which stops the server together with Caddy)
# only fires for a shell that is still alive when this process exits.
./caddy run --config Caddyfile
exit $?

#!/bin/sh
# Copied into the release folder as run.sh by build-release.sh - this file
# itself is never run from here. Starts the server (with the built web
# client mounted via EECK_WEB_DIST) in the foreground.
#
# For a real server, run this under systemd instead of by hand - see
# eeck.service.template next to this script (or already sitting alongside
# run.sh in the release folder) for a unit file to install.
set -eu
cd "$(dirname "$0")"

EECK_PORT="${1:-3001}"
export EECK_PORT
export EECK_WEB_DIST="web-dist"

# Permissions can get lost in transit (a zip download, some rsync/tar
# invocations) - restoring the exec bit here costs nothing and avoids a
# confusing "Permission denied" on first run.
chmod +x server/bin/server 2>/dev/null || true

echo "Starting eeck on port $EECK_PORT ..."
# exec replaces this shell with the JVM: Ctrl+C and `systemctl stop` signal
# it directly, and its exit code becomes this script's exit code.
exec ./server/bin/server

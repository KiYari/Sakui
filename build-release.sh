#!/bin/sh
# Builds the server and the web client, then assembles a self-contained
# release folder that runs as ONE process (no Docker, no nginx) on Linux -
# the server serves the built SPA itself when EECK_WEB_DIST points at it.
# See scripts/run-template.sh for what run.sh (generated below) actually does,
# and scripts/eeck.service.template for running it under systemd.
#
# Result: release/eeck-linux/run.sh -- run it to start the app on :3001 (or
# ./release/eeck-linux/run.sh 8080 for another port). Same output path
# build-release.bat's own Linux release (built via WSL) uses, so either one
# produces something at this location.
#
# Usage:
#   ./build-release.sh              # build here, then copy release/eeck-linux to the server
#   ./build-release.sh --on-server   # same, but skipped if already built - just
#                                    # documents that this can run directly on the
#                                    # target machine too, given a JDK and Node there

set -eu
cd "$(dirname "$0")"

CADDY_VERSION=2.11.4

echo "=== Building server (gradlew :server:installDist) ==="
./gradlew :server:installDist

echo
echo "=== Building web client (npm ci && npm run build) ==="
(cd web && npm ci && npm run build)

echo
echo "=== Assembling release/eeck-linux ==="
RELEASE_DIR="release/eeck-linux"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

cp -r server/build/install/server "$RELEASE_DIR/server"
cp -r web/dist "$RELEASE_DIR/web-dist"
cp scripts/run-template.sh "$RELEASE_DIR/run.sh"
cp scripts/eeck.service.template "$RELEASE_DIR/eeck.service.template"
chmod +x "$RELEASE_DIR/run.sh" "$RELEASE_DIR/server/bin/server"

echo
echo "=== Bundling Caddy $CADDY_VERSION (for open-internet HTTPS via run-https.sh) ==="
CADDY_OK=0
case "$(uname -m)" in
    x86_64) CADDY_ARCH=amd64 ;;
    aarch64|arm64) CADDY_ARCH=arm64 ;;
    *) CADDY_ARCH="" ;;
esac
if [ -z "$CADDY_ARCH" ]; then
    echo "Unrecognised CPU architecture ($(uname -m)) - skipping Caddy (run-https.sh will be unavailable)."
elif ! command -v curl >/dev/null 2>&1; then
    echo "curl not found - skipping Caddy (run-https.sh will be unavailable)."
else
    CADDY_TARBALL="$(mktemp -t eeck-caddy.XXXXXX)"
    CADDY_URL="https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_linux_${CADDY_ARCH}.tar.gz"
    if curl -sL -o "$CADDY_TARBALL" "$CADDY_URL"; then
        tar -xzf "$CADDY_TARBALL" -C "$RELEASE_DIR" caddy
        chmod +x "$RELEASE_DIR/caddy"
        cp scripts/Caddyfile-standalone.template "$RELEASE_DIR/Caddyfile"
        cp scripts/run-https-template.sh "$RELEASE_DIR/run-https.sh"
        cp scripts/eeck-caddy.service.template "$RELEASE_DIR/eeck-caddy.service.template"
        chmod +x "$RELEASE_DIR/run-https.sh"
        CADDY_OK=1
    else
        echo "Could not download Caddy - skipping (run-https.sh will be unavailable)."
    fi
    rm -f "$CADDY_TARBALL"
fi

echo
echo "Done. The release is in $RELEASE_DIR"
echo
echo "  $RELEASE_DIR/run.sh            (plain HTTP, starts on port 3001)"
echo "  $RELEASE_DIR/run.sh 8080       (or any other port)"
if [ "$CADDY_OK" = "1" ]; then
    echo
    echo "  $RELEASE_DIR/run-https.sh your-domain.example.com"
    echo "      (HTTPS for the open internet, via a bundled Caddy - needs that"
    echo "      domain's DNS already pointing here, ports 80/443 open inbound,"
    echo "      and permission to bind them. See run-https.sh itself for full"
    echo "      requirements.)"
fi
echo
echo "That folder is self-contained - tar/scp/rsync it to the server and"
echo "run.sh still works there. It needs a Java 21 runtime on PATH:"
echo "  apt install openjdk-21-jre-headless    (Debian/Ubuntu)"
echo "  dnf install java-21-openjdk-headless   (Fedora/RHEL)"
echo
echo "For a real server, install it as a systemd service instead of running"
echo "run.sh (or run-https.sh) by hand - see $RELEASE_DIR/eeck.service.template"
if [ "$CADDY_OK" = "1" ]; then
    echo "and $RELEASE_DIR/eeck-caddy.service.template for the steps."
else
    echo "for the steps."
fi

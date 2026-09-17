@echo off
rem Copied into the release folder as run-https.bat by build-release.bat.
rem Starts eeck bound to 127.0.0.1 only, and Caddy in front of it on ports
rem 80/443, terminating TLS with an automatic Let's Encrypt certificate.
rem
rem Requires:
rem   - This domain's DNS A record already pointing at this machine's
rem     public IP (Let's Encrypt has to reach this machine to verify it).
rem   - This script run as Administrator (binding ports 80/443 needs it).
rem   - Ports 80 and 443 open inbound (Windows Firewall, and your router if
rem     this machine is behind one) - Caddy needs both, even for an
rem     HTTPS-only site: port 80 answers the ACME challenge and redirects to
rem     443.
rem
rem Usage:  run-https.bat your-domain.example.com

if "%~1"=="" (
    echo Usage: run-https.bat your-domain.example.com
    exit /b 1
)

cd /d "%~dp0"

set EECK_DOMAIN=%~1
set EECK_HOST=127.0.0.1
set EECK_PORT=3001
set EECK_WEB_DIST=web-dist
rem Caddy connects from 127.0.0.1 itself; without this every client would
rem look like Caddy to the per-client rate limits and share one budget.
rem Caddy's reverse_proxy sets X-Forwarded-For to the real client itself.
set EECK_TRUST_PROXY=true

echo Starting eeck on 127.0.0.1:%EECK_PORT% (not reachable except through Caddy) ...
start "eeck-server" cmd /k ".\server\bin\server.bat"

timeout /t 2 /nobreak >nul

echo.
echo Starting Caddy for %EECK_DOMAIN% on ports 80/443 ...
echo Caddy's own logs (including certificate issuance) print below. Leave
echo this window open - closing it, or Ctrl+C, stops Caddy (the server
echo keeps running in its own "eeck-server" window until you close that too).
echo.
.\caddy.exe run --config Caddyfile

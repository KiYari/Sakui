@echo off
rem Copied into the release folder as run.bat by build-release.bat - this file
rem itself is never run from here. Starts the server (with the built web
rem client mounted via EECK_WEB_DIST) in its own window, then opens the app.

cd /d "%~dp0"

if "%~1"=="" (
    set EECK_PORT=3001
) else (
    set EECK_PORT=%~1
)
set EECK_WEB_DIST=web-dist

echo Starting eeck on port %EECK_PORT% ...
echo Server logs open in a separate "eeck-server" window - close it, or
echo press Ctrl+C in it, to stop the server.
start "eeck-server" cmd /k ".\server\bin\server.bat"

rem Give the JVM a moment to come up before pointing a browser at it.
timeout /t 2 /nobreak >nul
start "" "http://localhost:%EECK_PORT%/"

timeout /t 3 /nobreak >nul

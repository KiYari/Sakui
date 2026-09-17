@echo off
rem Builds the server and the web client, then assembles a self-contained
rem release folder that runs as ONE process (no Docker, no nginx) - the
rem server serves the built SPA itself when EECK_WEB_DIST points at it.
rem See run-template.bat for what run.bat (generated below) actually does.
rem
rem Result: release\eeck\run.bat  --  double-click it (or run from a
rem terminal) to start the app and open it in the browser.

setlocal
cd /d "%~dp0"

set CADDY_VERSION=2.11.4
rem Resolved here, not inside the if/else block below that uses it: cmd.exe
rem substitutes %VAR% in a whole parenthesized block at parse time, so a
rem variable set and read inside the *same* block reads as blank.
set CADDY_ZIP=%TEMP%\eeck-caddy-%CADDY_VERSION%.zip

echo === Building server (gradlew :server:installDist) ===
call .\gradlew.bat :server:installDist
if errorlevel 1 (
    echo.
    echo Server build failed - see the Gradle output above.
    exit /b 1
)

echo.
echo === Building web client (npm ci and npm run build) ===
pushd web
call npm ci
if errorlevel 1 (
    echo.
    echo npm ci failed - see the npm output above.
    popd
    exit /b 1
)
call npm run build
if errorlevel 1 (
    echo.
    echo Web client build failed - see the npm output above.
    popd
    exit /b 1
)
popd

echo.
echo === Assembling release\eeck ===
set RELEASE_DIR=release\eeck
if exist "%RELEASE_DIR%" rd /s /q "%RELEASE_DIR%"
mkdir "%RELEASE_DIR%"
if errorlevel 1 exit /b 1

xcopy /e /i /q "server\build\install\server" "%RELEASE_DIR%\server" >nul
if errorlevel 1 (
    echo Copying the server build failed.
    exit /b 1
)
xcopy /e /i /q "web\dist" "%RELEASE_DIR%\web-dist" >nul
if errorlevel 1 (
    echo Copying the web build failed.
    exit /b 1
)
copy /y "scripts\run-template.bat" "%RELEASE_DIR%\run.bat" >nul

echo.
echo === Bundling Caddy %CADDY_VERSION% (for open-internet HTTPS via run-https.bat) ===
set CADDY_OK=0
where curl.exe >nul 2>&1
if errorlevel 1 (
    echo curl.exe not found - skipping Caddy ^(run-https.bat will be unavailable^).
) else (
    where tar.exe >nul 2>&1
    if errorlevel 1 (
        echo tar.exe not found - skipping Caddy ^(run-https.bat will be unavailable^).
    ) else (
        curl.exe -sL -o "%CADDY_ZIP%" "https://github.com/caddyserver/caddy/releases/download/v%CADDY_VERSION%/caddy_%CADDY_VERSION%_windows_amd64.zip"
        if errorlevel 1 (
            echo Could not download Caddy - skipping ^(run-https.bat will be unavailable^).
        ) else (
            tar.exe -xf "%CADDY_ZIP%" -C "%RELEASE_DIR%" caddy.exe
            if errorlevel 1 (
                echo Could not extract Caddy - skipping ^(run-https.bat will be unavailable^).
            ) else (
                del "%CADDY_ZIP%" >nul 2>&1
                copy /y "scripts\Caddyfile-standalone.template" "%RELEASE_DIR%\Caddyfile" >nul
                copy /y "scripts\run-https-template.bat" "%RELEASE_DIR%\run-https.bat" >nul
                set CADDY_OK=1
            )
        )
    )
)

echo.
echo Done. The release is in %RELEASE_DIR%
echo.
echo   %RELEASE_DIR%\run.bat            (plain HTTP, starts on port 3001)
echo   %RELEASE_DIR%\run.bat 8080       (or any other port)
if "%CADDY_OK%"=="1" (
    echo.
    echo   %RELEASE_DIR%\run-https.bat your-domain.example.com
    echo       ^(HTTPS for the open internet, via a bundled Caddy - needs that
    echo       domain's DNS already pointing here, ports 80/443 open inbound,
    echo       and this run as Administrator. See run-https.bat itself for
    echo       full requirements.^)
)
echo.
echo That folder is self-contained - copy it anywhere (including another
echo Windows machine, or this same one) and run.bat still works. It needs
echo a Java 21 runtime on PATH; if none is installed, get one from
echo https://adoptium.net/ (Temurin JRE 21) first.

endlocal

@echo off
rem Builds the server and the web client once, then assembles TWO self-contained
rem release folders that each run as ONE process (no Docker, no nginx):
rem   release\eeck-windows  (see run-template.bat)
rem   release\eeck-linux    (see run-template.sh) -- via WSL, since that is the
rem                         only thing on a Windows box that reliably sets a
rem                         real Unix executable bit (chmod through plain
rem                         Windows tools does not stick on NTFS).
rem
rem The Linux folder is skipped, with a clear message, if WSL isn't installed
rem (wsl --install fixes that) - everything else in this script still runs.

setlocal
cd /d "%~dp0"

set CADDY_VERSION=2.11.4
rem Resolved here, not inside the if/else blocks below that use them: cmd.exe
rem substitutes %VAR% in a whole parenthesized block at parse time, so a
rem variable set and read inside the *same* block reads as blank.
set CADDY_ZIP_WIN=%TEMP%\eeck-caddy-windows-%CADDY_VERSION%.zip
set CADDY_TGZ_LINUX=%TEMP%\eeck-caddy-linux-%CADDY_VERSION%.tar.gz
set WIN_DIR=release\eeck-windows
set LINUX_DIR=release\eeck-linux

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

rem ================= Windows release =================
echo.
echo === Assembling %WIN_DIR% ===
if exist "%WIN_DIR%" rd /s /q "%WIN_DIR%"
mkdir "%WIN_DIR%"
if errorlevel 1 exit /b 1

xcopy /e /i /q "server\build\install\server" "%WIN_DIR%\server" >nul
if errorlevel 1 (
    echo Copying the server build failed.
    exit /b 1
)
xcopy /e /i /q "web\dist" "%WIN_DIR%\web-dist" >nul
if errorlevel 1 (
    echo Copying the web build failed.
    exit /b 1
)
copy /y "scripts\run-template.bat" "%WIN_DIR%\run.bat" >nul

echo.
echo === Bundling Windows Caddy %CADDY_VERSION% (for open-internet HTTPS via run-https.bat) ===
set CADDY_OK_WIN=0
where curl.exe >nul 2>&1
if errorlevel 1 (
    echo curl.exe not found - skipping Caddy ^(run-https.bat will be unavailable^).
) else (
    where tar.exe >nul 2>&1
    if errorlevel 1 (
        echo tar.exe not found - skipping Caddy ^(run-https.bat will be unavailable^).
    ) else (
        curl.exe -sL -o "%CADDY_ZIP_WIN%" "https://github.com/caddyserver/caddy/releases/download/v%CADDY_VERSION%/caddy_%CADDY_VERSION%_windows_amd64.zip"
        if errorlevel 1 (
            echo Could not download Caddy - skipping ^(run-https.bat will be unavailable^).
        ) else (
            tar.exe -xf "%CADDY_ZIP_WIN%" -C "%WIN_DIR%" caddy.exe
            if errorlevel 1 (
                echo Could not extract Caddy - skipping ^(run-https.bat will be unavailable^).
            ) else (
                del "%CADDY_ZIP_WIN%" >nul 2>&1
                copy /y "scripts\Caddyfile-standalone.template" "%WIN_DIR%\Caddyfile" >nul
                copy /y "scripts\run-https-template.bat" "%WIN_DIR%\run-https.bat" >nul
                set CADDY_OK_WIN=1
            )
        )
    )
)

rem ================= Linux release (via WSL) =================
echo.
set LINUX_OK=0
set CADDY_OK_LINUX=0
where wsl.exe >nul 2>&1
if errorlevel 1 (
    echo === Skipping %LINUX_DIR% - WSL not found ===
    echo Install it with "wsl --install" ^(one-time, needs a restart^) to also
    echo get a ready-to-run Linux release from this same command next time.
) else (
    echo === Assembling %LINUX_DIR% ===
    if exist "%LINUX_DIR%" rd /s /q "%LINUX_DIR%"
    mkdir "%LINUX_DIR%"

    xcopy /e /i /q "server\build\install\server" "%LINUX_DIR%\server" >nul
    xcopy /e /i /q "web\dist" "%LINUX_DIR%\web-dist" >nul
    copy /y "scripts\run-template.sh" "%LINUX_DIR%\run.sh" >nul
    copy /y "scripts\eeck.service.template" "%LINUX_DIR%\" >nul

    echo === Bundling Linux Caddy %CADDY_VERSION% ^(for open-internet HTTPS via run-https.sh^) ===
    where curl.exe >nul 2>&1
    if errorlevel 1 (
        echo curl.exe not found - skipping Caddy ^(run-https.sh will be unavailable^).
    ) else (
        where tar.exe >nul 2>&1
        if errorlevel 1 (
            echo tar.exe not found - skipping Caddy ^(run-https.sh will be unavailable^).
        ) else (
            curl.exe -sL -o "%CADDY_TGZ_LINUX%" "https://github.com/caddyserver/caddy/releases/download/v%CADDY_VERSION%/caddy_%CADDY_VERSION%_linux_amd64.tar.gz"
            if errorlevel 1 (
                echo Could not download Caddy - skipping ^(run-https.sh will be unavailable^).
            ) else (
                tar.exe -xzf "%CADDY_TGZ_LINUX%" -C "%LINUX_DIR%" caddy
                if errorlevel 1 (
                    echo Could not extract Caddy - skipping ^(run-https.sh will be unavailable^).
                ) else (
                    del "%CADDY_TGZ_LINUX%" >nul 2>&1
                    copy /y "scripts\Caddyfile-standalone.template" "%LINUX_DIR%\Caddyfile" >nul
                    copy /y "scripts\run-https-template.sh" "%LINUX_DIR%\run-https.sh" >nul
                    copy /y "scripts\eeck-caddy.service.template" "%LINUX_DIR%\" >nul
                    set CADDY_OK_LINUX=1
                )
            )
        )
    )

    rem Plain Windows tools (xcopy, tar.exe, PowerShell) do not reliably set a
    rem real Unix executable bit on NTFS - chmod through WSL's own filesystem
    rem view does (its DrvFs mount stores it as real metadata other Linux
    rem tools, and a real Linux box after a WSL-side transfer, read back
    rem correctly). Run.sh/run-https.sh also try to fix this themselves on
    rem first run, as a second line of defense if this step is skipped or the
    rem folder is later copied by a tool that drops the bit again.
    for /f "delims=" %%W in ('wsl.exe wslpath -a "%CD%\%LINUX_DIR%"') do set WSL_LINUX_DIR=%%W
    wsl.exe -e bash -lc "chmod +x '%WSL_LINUX_DIR%/run.sh' '%WSL_LINUX_DIR%/run-https.sh' '%WSL_LINUX_DIR%/server/bin/server' '%WSL_LINUX_DIR%/caddy' 2>/dev/null; true"
    set LINUX_OK=1
)

rem ================= Summary =================
echo.
echo ================================================================
echo Done.
echo.
echo   %WIN_DIR%\run.bat            (plain HTTP, starts on port 3001)
echo   %WIN_DIR%\run.bat 8080       (or any other port)
if "%CADDY_OK_WIN%"=="1" (
    echo   %WIN_DIR%\run-https.bat your-domain.example.com
    echo       ^(HTTPS for the open internet - needs that domain's DNS already
    echo       pointing here, ports 80/443 open inbound, and this run as
    echo       Administrator. See run-https.bat itself for full requirements.^)
)
if "%LINUX_OK%"=="1" (
    echo.
    echo   %LINUX_DIR%\run.sh            ^(plain HTTP, starts on port 3001^)
    echo   %LINUX_DIR%\run.sh 8080       ^(or any other port^)
    if "%CADDY_OK_LINUX%"=="1" (
        echo   %LINUX_DIR%\run-https.sh your-domain.example.com
        echo       ^(same idea, for a real Linux server - see run-https.sh itself.^)
    )
    echo   %LINUX_DIR%\eeck.service.template ^(and eeck-caddy.service.template^)
    echo       have the steps to run it under systemd instead of by hand.
)
echo.
echo Both folders are self-contained - copy either one anywhere (another
echo machine of that OS, or this one) and its run script still works. Each
echo needs a Java 21 runtime on the target: Windows gets a reminder below;
echo Linux, see the apt/dnf lines in %LINUX_DIR%'s own build notes above.
echo   https://adoptium.net/ (Temurin JRE 21) for Windows.

endlocal

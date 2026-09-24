@echo off
setlocal
pushd "%~dp0" || exit /b 1

where pixi >nul 2>nul
if errorlevel 1 (
    echo ERROR: Install Pixi and make sure pixi is on PATH. 1>&2
    popd
    exit /b 1
)
if not exist "ext\rusefi\pixi.toml" (
    echo ERROR: Initialize rusEFI with git submodule update --init --recursive. 1>&2
    popd
    exit /b 1
)

pixi run --manifest-path "ext\rusefi\pixi.toml" bash compile_firmware.sh %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

@echo off
setlocal
set "RUSEFI_CUSTOM_JAVA_UI_DIR=%~dp0..\java-custom-ui"
pushd "%~dp0..\ext\rusefi"
if "%~1"=="" (
    call gradlew.bat -q --console=plain :custom-java-ui:runM749Cli
) else (
    call gradlew.bat -q --console=plain :custom-java-ui:runM749Cli "-Pm749Args=%*"
)
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

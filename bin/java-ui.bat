@echo off
setlocal
set "RUSEFI_CUSTOM_JAVA_UI_DIR=%~dp0..\java-custom-ui"
pushd "%~dp0..\ext\rusefi"
if "%~1"=="" (
    call gradlew.bat :custom-java-ui:test :ui:shadowJar
) else (
    call gradlew.bat %*
)
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

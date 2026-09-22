@echo off
setlocal
set "RUSEFI_CUSTOM_JAVA_UI_DIR=%~dp0..\java-custom-ui"
pushd "%~dp0..\ext\rusefi"
call gradlew.bat -q --console=plain :custom-java-ui:installM749Cli
set "RESULT=%ERRORLEVEL%"
popd
if not "%RESULT%"=="0" exit /b %RESULT%
set "JAVA_BIN=java"
if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
set "PATH=%~dp0..\ext\rusefi\java_console;%PATH%"
"%JAVA_BIN%" -cp "%~dp0..\java-custom-ui\build\install\m749\lib\*" com.rusefi.m749.M749Cli %*
exit /b %ERRORLEVEL%

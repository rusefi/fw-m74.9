@echo off
call "%~dp0m749-cli.bat" --write-flash %*
exit /b %ERRORLEVEL%

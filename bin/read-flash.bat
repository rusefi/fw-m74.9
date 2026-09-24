@echo off
call "%~dp0m749-cli.bat" --read-flash %*
exit /b %ERRORLEVEL%

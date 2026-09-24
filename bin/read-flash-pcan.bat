@echo off
call "%~dp0m749-cli.bat" --read-flash %* --channel auto
exit /b %ERRORLEVEL%

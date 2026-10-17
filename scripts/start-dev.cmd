@echo off
rem Double-click or run from any directory: restarts the Velocity backend and console dev server.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dev.ps1" %*

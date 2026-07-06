@echo off
setlocal
powershell -ExecutionPolicy Bypass -File "%~dp0build-agent-exe.ps1"

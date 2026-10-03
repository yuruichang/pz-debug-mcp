@echo off
setlocal
set PYTHONUTF8=1
if not exist "%~dp0.venv\Scripts\python.exe" (
  echo Run setup.ps1 first. 1>&2
  exit /b 1
)
"%~dp0.venv\Scripts\python.exe" -m pz_debug_mcp.server %*
exit /b %errorlevel%

@echo off
REM ============================================================================
REM MeshDrop - Single-Command Windows Launcher Shortcut
REM ============================================================================
REM Double-click or run from Command Prompt to start MeshDrop.
REM Delegates to PowerShell start-meshdrop.ps1 with execution bypass.
REM ============================================================================

setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-meshdrop.ps1" %*
exit /b %ERRORLEVEL%

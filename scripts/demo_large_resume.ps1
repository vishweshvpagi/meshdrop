# ==============================================================================
# MeshDrop - Large File Resumption & Recovery Automated Demo
# ==============================================================================
# Demonstrates large-file transfer interruption mid-stream, crash-safe checkpoint
# inspection, reconnection, receiver-authoritative resume, and SHA-256 verification.
# ==============================================================================

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# 1. Compile test and demo sources
Write-Host "Compiling MeshDrop sources..." -ForegroundColor Yellow
& powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\test.ps1" "IdentityFingerprintTest" *>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# 2. Run LargeResumeDemoRunner
Write-Host "`nExecuting Large File Resumption & Recovery Demo..." -ForegroundColor Yellow
java -enableassertions -cp "out" com.meshdrop.demo.LargeResumeDemoRunner

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nLarge File Resume Demo Completed with Status: SUCCESS" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nLarge File Resume Demo FAILED with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

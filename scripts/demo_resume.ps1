# ==============================================================================
# MeshDrop - Resumable File Transfer Automated Demo
# ==============================================================================
# Demonstrates live file transfer interruption, crash-safe checkpoint
# inspection, reconnection, receiver-authoritative resume, and SHA-256 verification.
# ==============================================================================

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# 1. Ensure test classes are compiled
Write-Host "Compiling MeshDrop sources..." -ForegroundColor Yellow
& powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\test.ps1" "IdentityFingerprintTest" *>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# 2. Run ResumeDemoRunner
Write-Host "`nExecuting Resumable Transfer Demo..." -ForegroundColor Yellow
java -enableassertions -cp "out" com.meshdrop.demo.ResumeDemoRunner

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nResume Demo Completed with Status: SUCCESS" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nResume Demo FAILED with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

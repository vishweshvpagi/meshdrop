# ==============================================================================
# MeshDrop - Large File Streaming Transfer Automated Demo
# ==============================================================================
# Demonstrates large-file streaming transfer with sliding-window flow control,
# real-time throughput metrics, bounded memory (< 32 MB heap), and SHA-256 verification.
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

# 2. Run LargeTransferDemoRunner
Write-Host "`nExecuting Large File Streaming Transfer Demo..." -ForegroundColor Yellow
java -enableassertions -cp "out" com.meshdrop.demo.LargeTransferDemoRunner

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nLarge File Transfer Demo Completed with Status: SUCCESS" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nLarge File Transfer Demo FAILED with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

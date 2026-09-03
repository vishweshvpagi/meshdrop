# ==============================================================================
# MeshDrop - Live Multi-Node Automated Demo
# ==============================================================================
# Demonstrates two live MeshDrop nodes discovering, connecting, handshaking,
# exchanging messages, transferring files, verifying SHA-256 integrity,
# and gracefully shutting down on real TCP/UDP sockets.
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

# 2. Run LiveDemoRunner
Write-Host "`nExecuting Live P2P Demo..." -ForegroundColor Yellow
java -enableassertions -cp "out" com.meshdrop.demo.LiveDemoRunner

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nLive Demo Completed with Status: SUCCESS" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nLive Demo FAILED with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

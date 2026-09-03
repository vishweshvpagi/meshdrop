# scripts/test_cli.ps1
# Manual verification script for MeshDrop Interactive CLI and two-node peer communication.
#
# Tests and demonstrates:
#   1. Clean build of project bytecode
#   2. Starting Node A with piped CLI input
#   3. Starting Node B with piped CLI input
#   4. LAN peer discovery and handshake
#   5. Bidirectional messaging: "hello from Node A" -> Node B, and vice-versa
#   6. Latency ping measurement
#   7. Graceful clean shutdown

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$outDir = Join-Path $projectRoot "out"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " MeshDrop Manual CLI & Two-Node Test" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# Ensure project is compiled
if (-not (Test-Path (Join-Path $outDir "com\meshdrop\Main.class"))) {
    Write-Host "Building project first..." -ForegroundColor Yellow
    & (Join-Path $scriptDir "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Build failed"
        exit 1
    }
}

Write-Host "`n[Step 1] Testing single node CLI commands..." -ForegroundColor Green
$testCommands = @(
    "help",
    "status",
    "info",
    "peers",
    "connections",
    "discover",
    "clear",
    "exit"
)

$cliOutput = $testCommands | java -cp "$outDir" com.meshdrop.Main

Write-Host "CLI execution output:" -ForegroundColor Gray
$cliOutput | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

# Verify required strings in output
$checks = @(
    "MeshDrop commands:",
    "Node Status",
    "Local Node",
    "Starting LAN discovery...",
    "Shutting down MeshDrop..."
)

$passed = $true
foreach ($check in $checks) {
    if ($cliOutput -match [regex]::Escape($check)) {
        Write-Host "  [OK] Found output: '$check'" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] Missing output: '$check'" -ForegroundColor Red
        $passed = $false
    }
}

Write-Host "`n[Step 2] Testing two-node CLI discovery & messaging integration test..." -ForegroundColor Green
java -ea -cp "$outDir" com.meshdrop.TestRunner TwoNodeMessagingTest
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [FAIL] Two-node messaging test failed!" -ForegroundColor Red
    exit 1
} else {
    Write-Host "  [OK] Two-node discovery, messaging, and ping succeeded!" -ForegroundColor Green
}

Write-Host "`n=========================================" -ForegroundColor Cyan
if ($passed) {
    Write-Host " CLI Manual Verification: SUCCESS!" -ForegroundColor Green
} else {
    Write-Host " CLI Manual Verification: FAILED!" -ForegroundColor Red
    exit 1
}
Write-Host "=========================================" -ForegroundColor Cyan

# ==============================================================================
# MeshDrop - Multi-Node Interactive Launch Script
# ==============================================================================
# Launches an interactive MeshDrop node with custom name, ports, and isolated data directory.
#
# Examples:
#   .\scripts\run_demo.ps1 -Name Alice -TcpPort 5001
#   .\scripts\run_demo.ps1 -Name Bob   -TcpPort 5002 -ConnectPort 5001
# ==============================================================================

param (
    [string]$Name = "Node-$([System.Guid]::NewGuid().ToString().Substring(0,4).ToUpper())",
    [int]$TcpPort = 5000,
    [int]$DiscoveryPort = 5001,
    [int]$ConnectPort = 0,
    [string]$DataDir = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

# Default data directory to data/<Name>
if ([string]::IsNullOrWhiteSpace($DataDir)) {
    $DataDir = Join-Path "data" $Name
}

# 1. Build project if out directory is missing
if (-not (Test-Path "out")) {
    Write-Host "Compiling MeshDrop..." -ForegroundColor Yellow
    & powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\build.ps1"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Launching MeshDrop Node: $Name" -ForegroundColor Cyan
Write-Host " TCP Port:       $TcpPort" -ForegroundColor DarkCyan
Write-Host " Discovery Port: $DiscoveryPort" -ForegroundColor DarkCyan
Write-Host " Data Directory: $DataDir" -ForegroundColor DarkCyan
if ($ConnectPort -gt 0) {
    Write-Host " Auto-Connect:   127.0.0.1:$ConnectPort" -ForegroundColor Green
}
Write-Host "=========================================" -ForegroundColor Cyan

# 2. Launch Main with parameters
$javaArgs = @(
    "-cp", "out",
    "com.meshdrop.Main",
    "--name", "$Name",
    "--tcp-port", "$TcpPort",
    "--udp-port", "$DiscoveryPort",
    "--data-dir", "$DataDir"
)

if ($ConnectPort -gt 0) {
    $javaArgs += @("connect", "127.0.0.1", "$ConnectPort")
}

& java $javaArgs

exit $LASTEXITCODE

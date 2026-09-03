# ==============================================================================
# MeshDrop - Demo File Generator
# ==============================================================================
# Creates a deterministic binary file for transfer demonstrations.
# ==============================================================================

param (
    [string]$Path = "demo_file.bin",
    [int]$SizeKB = 256
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$targetPath = [System.IO.Path]::GetFullPath($Path)

Write-Host "Generating deterministic file ($SizeKB KB) at $targetPath..." -ForegroundColor Yellow

$totalBytes = $SizeKB * 1024
$buffer = New-Object byte[] $totalBytes

# Populate with deterministic pseudo-random pattern
for ($i = 0; $i -lt $totalBytes; $i++) {
    $buffer[$i] = [byte](($i * 41 + 19) % 256)
}

[System.IO.File]::WriteAllBytes($targetPath, $buffer)

# Calculate SHA-256
$hasher = [System.Security.Cryptography.SHA256]::Create()
$hashBytes = $hasher.ComputeHash($buffer)
$sha256 = [System.BitConverter]::ToString($hashBytes).Replace("-", "").ToLowerInvariant()

Write-Host "File created successfully!" -ForegroundColor Green
Write-Host "Size:    $([Math]::Round($totalBytes / 1024, 1)) KB ($totalBytes bytes)" -ForegroundColor Cyan
Write-Host "SHA-256: $sha256" -ForegroundColor Cyan

return $sha256

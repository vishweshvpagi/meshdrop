# scripts/clean.ps1
# Cleans the build outputs.

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$outDir = Join-Path $projectRoot "out"

Write-Host "Cleaning output directory $outDir..." -ForegroundColor Yellow

if (Test-Path $outDir) {
    Get-ChildItem -Path $outDir -Exclude ".gitkeep" | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Clean completed." -ForegroundColor Green
} else {
    Write-Host "Output directory does not exist." -ForegroundColor Green
}

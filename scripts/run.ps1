# scripts/run.ps1
# Runs the MeshDrop application.

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$outDir = Join-Path $projectRoot "out"

if (-not (Test-Path (Join-Path $outDir "com\meshdrop\Main.class"))) {
    Write-Host "Project not built yet. Triggering build first..." -ForegroundColor Yellow
    & (Join-Path $scriptDir "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

java -cp "$outDir" com.meshdrop.Main $args
exit $LASTEXITCODE

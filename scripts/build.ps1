# scripts/build.ps1
# Builds the MeshDrop project without external build tools using standard javac.

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")

$srcDir = Join-Path $projectRoot "src\com"
$outDir = Join-Path $projectRoot "out"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Building MeshDrop (Java 26)" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

if (-not (Test-Path $srcDir)) {
    Write-Error "Source directory not found: $srcDir"
    exit 1
}

# Ensure output directory exists
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

# Discover all .java files under src/com
$sources = Get-ChildItem -Path $srcDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

if ($sources.Count -eq 0) {
    Write-Error "No Java source files found under $srcDir"
    exit 1
}

Write-Host "Compiling $($sources.Count) source file(s) to $outDir..." -ForegroundColor Yellow

$sourcesFile = Join-Path $outDir "sources.txt"
# Write without UTF-8 BOM for javac compatibility
[System.IO.File]::WriteAllLines($sourcesFile, $sources, (New-Object System.Text.UTF8Encoding($false)))

try {
    javac -encoding UTF-8 -d "$outDir" "@$sourcesFile"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Compilation failed with exit code $LASTEXITCODE" -ForegroundColor Red
        exit $LASTEXITCODE
    }
    Write-Host "Build SUCCESS!" -ForegroundColor Green
    exit 0
} catch {
    Write-Host "Build failed: $_" -ForegroundColor Red
    exit 1
} finally {
    if (Test-Path $sourcesFile) {
        Remove-Item $sourcesFile -Force -ErrorAction SilentlyContinue
    }
}

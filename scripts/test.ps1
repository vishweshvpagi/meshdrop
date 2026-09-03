# scripts/test.ps1
# Compiles source and test files and executes TestRunner.

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")

$srcDir = Join-Path $projectRoot "src"
$outDir = Join-Path $projectRoot "out"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Running MeshDrop Tests (Java 26)" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

# Discover all .java files under src (both src/com and src/test)
$sources = Get-ChildItem -Path $srcDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

if ($sources.Count -eq 0) {
    Write-Error "No Java source files found under $srcDir"
    exit 1
}

Write-Host "Compiling $($sources.Count) source & test file(s)..." -ForegroundColor Yellow

$sourcesFile = Join-Path $outDir "test_sources.txt"
# Write without UTF-8 BOM for javac compatibility
[System.IO.File]::WriteAllLines($sourcesFile, $sources, (New-Object System.Text.UTF8Encoding($false)))

try {
    javac -encoding UTF-8 -d "$outDir" "@$sourcesFile"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Test compilation failed with exit code $LASTEXITCODE" -ForegroundColor Red
        exit $LASTEXITCODE
    }

    Write-Host "Executing TestRunner..." -ForegroundColor Yellow
    java -enableassertions -cp "$outDir" com.meshdrop.TestRunner $args
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Tests FAILED with exit code $LASTEXITCODE" -ForegroundColor Red
        exit $LASTEXITCODE
    }
    Write-Host "All tests PASSED!" -ForegroundColor Green
    exit 0
} catch {
    Write-Host "Test execution failed: $_" -ForegroundColor Red
    exit 1
} finally {
    if (Test-Path $sourcesFile) {
        Remove-Item $sourcesFile -Force -ErrorAction SilentlyContinue
    }
}

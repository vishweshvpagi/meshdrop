$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$srcDir = Join-Path $projectRoot "src"
$outDir = Join-Path $projectRoot "out"

$sources = Get-ChildItem -Path $srcDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$sourcesFile = Join-Path $outDir "test_sources.txt"
[System.IO.File]::WriteAllLines($sourcesFile, $sources, (New-Object System.Text.UTF8Encoding($false)))

javac -encoding UTF-8 -d "$outDir" "@$sourcesFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

java -ea -cp "$outDir" com.meshdrop.transfer.Real1GBTransferTest

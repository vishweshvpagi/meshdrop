# ==============================================================================
# MeshDrop - Phase 12 Resumable File Transfers Verification Script
# ==============================================================================
# Runs all 20 automated resume and transfer reliability test suites.
# ==============================================================================

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " MeshDrop Phase 12 Resumable Transfer Tests" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Build project
Write-Host "`n[1/3] Compiling MeshDrop..." -ForegroundColor Yellow
& powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\build.ps1"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# 2. Run all Phase 12 Resume Test Suites
Write-Host "`n[2/3] Executing 20 Phase 12 Resumable Transfer Test Suites..." -ForegroundColor Yellow

$ResumeSuites = @(
    "TransferCheckpointTest",
    "CheckpointAtomicWriteTest",
    "ResumeRequestCodecTest",
    "ResumeResponseCodecTest",
    "InterruptedTransferTest",
    "ResumeTransferTest",
    "NoDuplicateDataTest",
    "WrongOffsetTest",
    "CheckpointMismatchTest",
    "MetadataMismatchResumeTest",
    "CompletedTransferResumeTest",
    "RestartRecoveryTest",
    "TwoNodeResumeTest",
    "ResumeAfterRestartTest",
    "CancelTransferTest",
    "ConcurrentResumeTest",
    "NetworkFailureTest",
    "LargeFileResumeTest",
    "CorruptedPartialFileTest",
    "PathTraversalRecoveryTest"
)

$Failed = 0
foreach ($Suite in $ResumeSuites) {
    Write-Host "  -> Running $Suite..." -NoNewline
    & java -cp "out;src;src/test" -ea com.meshdrop.TestRunner $Suite *>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host " [PASSED]" -ForegroundColor Green
    } else {
        Write-Host " [FAILED]" -ForegroundColor Red
        $Failed++
    }
}

if ($Failed -gt 0) {
    Write-Host "`n$Failed test suite(s) failed!" -ForegroundColor Red
    exit 1
}

Write-Host "`n[3/3] All 20 Phase 12 Resumable Transfer Test Suites Passed Successfully!" -ForegroundColor Green

Write-Host @"

==============================================================================
 PHASE 12 VERIFICATION COMPLETE: ALL 20 RESUME TEST SUITES PASSED!
==============================================================================
"@ -ForegroundColor Cyan
exit 0

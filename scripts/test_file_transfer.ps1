# ==============================================================================
# MeshDrop - Phase 11 File Transfer Verification Script
# ==============================================================================
# Runs all automated file transfer test suites and verifies end-to-end P2P file transfers.
# ==============================================================================

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " MeshDrop Phase 11 File Transfer Tests" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Build project
Write-Host "`n[1/3] Compiling MeshDrop..." -ForegroundColor Yellow
& powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\build.ps1"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# 2. Run all File Transfer Test Suites
Write-Host "`n[2/3] Executing File Transfer Test Suites..." -ForegroundColor Yellow

$TransferSuites = @(
    "FileMetadataTest",
    "FileChunkTest",
    "FileMetadataCodecTest",
    "FileChunkCodecTest",
    "FileHashTest",
    "FileSenderTest",
    "FileReceiverTest",
    "TransferStateTest",
    "TransferManagerTest",
    "FileTransferServiceTest",
    "TwoNodeFileTransferTest",
    "BinaryFileTransferTest",
    "LargeFileTransferTest",
    "UnicodeFilenameTest",
    "CollisionTest",
    "PathTraversalTest",
    "HashMismatchTest",
    "DisconnectDuringTransferTest",
    "ConcurrentTransfersTest",
    "ShutdownDuringTransferTest"
)

$Failed = 0
foreach ($Suite in $TransferSuites) {
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

Write-Host "`n[3/3] All 20 File Transfer Test Suites Passed Successfully!" -ForegroundColor Green

Write-Host @"

==============================================================================
 MANUAL TWO-TERMINAL TEST PROCEDURE
==============================================================================

To verify MeshDrop file transfer manually between two interactive terminals:

Terminal 1 (Alice):
-------------------
powershell -NoExit -Command "cd C:\Users\VBP\Desktop\SocketStuff; java -cp out com.meshdrop.Main --name Alice --tcp-port 5001 --udp-port 5002"

meshdrop> discover
meshdrop> peers

Terminal 2 (Bob):
-----------------
powershell -NoExit -Command "cd C:\Users\VBP\Desktop\SocketStuff; java -cp out com.meshdrop.Main --name Bob --tcp-port 5003 --udp-port 5004"

meshdrop> discover
meshdrop> peers

To send a file from Alice to Bob:
---------------------------------
1. In Terminal 1 (Alice):
   meshdrop> sendfile Bob "README.md"

2. In Terminal 2 (Bob):
   You will see:
   Incoming file transfer:
   From: Alice
   File: README.md
   Size: ...
   Accept? [y/N]
   
   Type: y

3. Observe progress and transfer completion:
   Progress: 100%
   SHA-256 verified.
   Transfer completed.

4. Check transfers list:
   meshdrop> transfers

5. Check destination:
   The file is safely stored in: downloads/README.md

==============================================================================
"@ -ForegroundColor Cyan

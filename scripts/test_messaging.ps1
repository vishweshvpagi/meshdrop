# scripts/test_messaging.ps1
# Manual verification and end-to-end messaging test script for MeshDrop Phase 10.
#
# Tests and demonstrates:
#   1. Clean build of project bytecode
#   2. Running all automated Phase 10 messaging unit and integration tests:
#      - MessageTest, MessageCodecTest, MessageServiceTest, MessageListenerTest
#      - MessageDeduplicationTest, SenderIdentityValidationTest, RecipientValidationTest
#      - MessageAckTest, MessageAckTimeoutTest, ConcurrentMessagingTest
#      - UnicodeMessagingTest, ShutdownMessagingTest, TwoNodeMessagingTest
#   3. Documented procedure for live two-node manual interactive messaging

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$outDir = Join-Path $projectRoot "out"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " MeshDrop Phase 10 Messaging Test Suite" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Ensure project is built
if (-not (Test-Path (Join-Path $outDir "com\meshdrop\Main.class"))) {
    Write-Host "Building project first..." -ForegroundColor Yellow
    & (Join-Path $scriptDir "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Build failed"
        exit 1
    }
}

# 2. Run Phase 10 Messaging Tests
Write-Host "`n[Step 1] Running Phase 10 Unit, ACK, and Concurrency Test Suites..." -ForegroundColor Green
$messagingSuites = @(
    "MessageTest",
    "MessageCodecTest",
    "MessageServiceTest",
    "MessageListenerTest",
    "MessageDeduplicationTest",
    "SenderIdentityValidationTest",
    "RecipientValidationTest",
    "MessageAckTest",
    "MessageAckTimeoutTest",
    "ConcurrentMessagingTest",
    "UnicodeMessagingTest",
    "ShutdownMessagingTest",
    "TwoNodeMessagingTest"
)

$allPassed = $true
foreach ($suite in $messagingSuites) {
    Write-Host "Running $suite..." -ForegroundColor Gray
    java -ea -cp "$outDir" com.meshdrop.TestRunner $suite
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [FAIL] $suite failed!" -ForegroundColor Red
        $allPassed = $false
    }
}

if (-not $allPassed) {
    Write-Host "`n[FAIL] One or more messaging tests failed!" -ForegroundColor Red
    exit 1
}

Write-Host "`n=========================================" -ForegroundColor Cyan
Write-Host " All 13 Messaging Suites PASSED!" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Cyan

Write-Host @"

------------------------------------------------------------------------
MANUAL TWO-NODE INTERACTIVE MESSAGING INSTRUCTIONS
------------------------------------------------------------------------

To test live interactive messaging between two independent processes:

Terminal 1 (Alice):
  java -cp out com.meshdrop.Main --name Alice --tcp-port 5000 --udp-port 5001

Terminal 2 (Bob):
  java -cp out com.meshdrop.Main --name Bob --tcp-port 5002 --udp-port 5003

In Alice's terminal:
  meshdrop> discover
  meshdrop> peers
  meshdrop> connections
  meshdrop> send Bob "Hello from Alice"
  meshdrop> ping Bob

Verify Bob receives:
  [HH:mm:ss] Alice:
  Hello from Alice

In Bob's terminal:
  meshdrop> send Alice "Hello back from Bob"

Finally, exit both nodes:
  meshdrop> exit
------------------------------------------------------------------------

"@ -ForegroundColor Yellow

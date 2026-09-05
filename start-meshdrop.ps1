# ==============================================================================
# MeshDrop - Single-Command Local Application Launcher
# ==============================================================================
# Starts the Java backend and React frontend, verifies readiness, opens the
# browser, monitors processes, and cleanly terminates child processes on Ctrl+C.
#
# Usage:
#   .\start-meshdrop.ps1             # Launch in Development mode (Vite HMR)
#   .\start-meshdrop.ps1 -Production # Launch in Production mode (Pre-built assets)
#   .\start-meshdrop.ps1 -NoBrowser  # Do not open browser automatically
# ==============================================================================

[CmdletBinding()]
param(
    [switch]$Production,
    [switch]$NoBrowser,
    [int]$BackendPort = 8080,
    [int]$TcpPort = 5000,
    [int]$UdpPort = 5001,
    [int]$FrontendPort = 3000
)

$ErrorActionPreference = "Continue"

# ------------------------------------------------------------------------------
# 1. Resolve Paths & Setup Logs
# ------------------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $ScriptDir) { $ScriptDir = Get-Location }
$BackendRoot = (Resolve-Path $ScriptDir).Path

# Dynamically locate the frontend directory relative to the repository
$PossibleFrontendDirs = @(
    (Join-Path $BackendRoot "meshdropfrontend"),
    (Join-Path $BackendRoot "meshdrop-frontend"),
    (Join-Path $BackendRoot "..\meshdropfrontend"),
    (Join-Path $BackendRoot "..\meshdrop-frontend")
)
$FrontendRoot = $null
foreach ($candidate in $PossibleFrontendDirs) {
    if (Test-Path (Join-Path $candidate "package.json")) {
        $FrontendRoot = (Resolve-Path $candidate).Path
        break
    }
}

$LogsDir = Join-Path $BackendRoot "logs"
if (-not (Test-Path $LogsDir)) {
    New-Item -ItemType Directory -Path $LogsDir -Force | Out-Null
}

$BackendLog = Join-Path $LogsDir "backend.log"
$BackendErrLog = Join-Path $LogsDir "backend.err.log"
$FrontendLog = Join-Path $LogsDir "frontend.log"
$FrontendErrLog = Join-Path $LogsDir "frontend.err.log"
$LauncherLog = Join-Path $LogsDir "launcher.log"

function Log-Launcher([string]$message) {
    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $entry = "[$timestamp] $message"
    Add-Content -Path $LauncherLog -Value $entry -ErrorAction SilentlyContinue
}

# ------------------------------------------------------------------------------
# 2. Display Banner
# ------------------------------------------------------------------------------
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "             MESHDROP                   " -ForegroundColor Cyan
Write-Host "      Local P2P File Transfer           " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Log-Launcher "Starting MeshDrop launcher (Mode: $(if ($Production) { 'Production' } else { 'Development' }))"

# ------------------------------------------------------------------------------
# 3. Check Environment & Prerequisites
# ------------------------------------------------------------------------------
Write-Host "[1/4] Checking environment..." -ForegroundColor Yellow

# Check Java
$javaCmd = Get-Command "java" -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Host "[ERROR] Java was not found on PATH." -ForegroundColor Red
    Write-Host "Please install Java (Java 26 or compatible) or configure JAVA_HOME." -ForegroundColor Yellow
    Log-Launcher "ERROR: Java not found"
    exit 1
}

$javaVerLine = try {
    $tmpFile = Join-Path $LogsDir "java_ver.tmp"
    $p = Start-Process -FilePath "java" -ArgumentList "-version" -NoNewWindow -PassThru -RedirectStandardError $tmpFile
    $p.WaitForExit(3000) | Out-Null
    if (Test-Path $tmpFile) {
        $firstLine = (Get-Content $tmpFile -TotalCount 1).Trim()
        Remove-Item $tmpFile -Force -ErrorAction SilentlyContinue
        $firstLine
    } else { "Java detected" }
} catch { "Java detected" }

Write-Host "  [OK] Java ($javaVerLine)" -ForegroundColor Green
Log-Launcher "Found Java: $javaVerLine"

# Check Node.js
$nodeCmd = Get-Command "node" -ErrorAction SilentlyContinue
if (-not $nodeCmd) {
    Write-Host "[ERROR] Node.js was not found on PATH." -ForegroundColor Red
    Write-Host "Please install Node.js (v18+) and ensure it is available on your PATH." -ForegroundColor Yellow
    Log-Launcher "ERROR: Node.js not found"
    exit 1
}
$nodeVer = try {
    $tmpNode = Join-Path $LogsDir "node_ver.tmp"
    $np = Start-Process -FilePath "node" -ArgumentList "-v" -NoNewWindow -PassThru -RedirectStandardOutput $tmpNode
    $np.WaitForExit(3000) | Out-Null
    if (Test-Path $tmpNode) {
        $v = (Get-Content $tmpNode -TotalCount 1).Trim()
        Remove-Item $tmpNode -Force -ErrorAction SilentlyContinue
        $v
    } else { "Node.js detected" }
} catch { "Node.js detected" }

Write-Host "  [OK] Node ($nodeVer)" -ForegroundColor Green
Log-Launcher "Found Node.js: $nodeVer"

# Check npm
$npmCmdName = if ($IsWindows -or $env:OS -like "*Windows*") { "npm.cmd" } else { "npm" }
$npmCmd = Get-Command $npmCmdName -ErrorAction SilentlyContinue
if (-not $npmCmd) {
    $npmCmd = Get-Command "npm" -ErrorAction SilentlyContinue
}
if (-not $npmCmd) {
    Write-Host "[ERROR] npm was not found on PATH." -ForegroundColor Red
    Write-Host "Please ensure npm is installed and accessible on your PATH." -ForegroundColor Yellow
    Log-Launcher "ERROR: npm not found"
    exit 1
}

$npmVer = try {
    $tmpNpm = Join-Path $LogsDir "npm_ver.tmp"
    $npmp = Start-Process -FilePath $npmCmd.Source -ArgumentList "-v" -NoNewWindow -PassThru -RedirectStandardOutput $tmpNpm
    $npmp.WaitForExit(3000) | Out-Null
    if (Test-Path $tmpNpm) {
        $v = (Get-Content $tmpNpm -TotalCount 1).Trim()
        Remove-Item $tmpNpm -Force -ErrorAction SilentlyContinue
        $v
    } else { "npm detected" }
} catch { "npm detected" }

Write-Host "  [OK] npm ($npmVer)" -ForegroundColor Green
Log-Launcher "Found npm: $npmVer"

# Verify Frontend Directory
if (-not $FrontendRoot) {
    Write-Host "[ERROR] Could not find the meshdrop-frontend directory containing package.json." -ForegroundColor Red
    Write-Host "Expected at: $(Join-Path $BackendRoot '..\meshdropfrontend') or $(Join-Path $BackendRoot 'meshdropfrontend')" -ForegroundColor Yellow
    Log-Launcher "ERROR: Frontend directory not located"
    exit 1
}
Write-Host "  [OK] Frontend directory ($FrontendRoot)" -ForegroundColor Green

# ------------------------------------------------------------------------------
# 4. Port Conflict Inspection
# ------------------------------------------------------------------------------
function Test-PortOpen([int]$port) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Where-Object { $_.State -eq "Listen" }
    return [bool]$conn
}

$backendAlreadyRunning = $false
if (Test-PortOpen $BackendPort) {
    try {
        $statusResp = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/api/status" -TimeoutSec 1 -ErrorAction Stop
        if ($statusResp -and $statusResp.running -eq $true) {
            $backendAlreadyRunning = $true
            Write-Host "  [NOTE] An existing healthy MeshDrop backend is already active on port $BackendPort. Reusing instance." -ForegroundColor Cyan
            Log-Launcher "Reusing active MeshDrop backend on port $BackendPort"
        }
    } catch {
        Write-Host "[ERROR] Port $BackendPort is already in use by another application." -ForegroundColor Red
        Write-Host "Please free port $BackendPort or configure a different port using -BackendPort." -ForegroundColor Yellow
        Log-Launcher "ERROR: Port $BackendPort in use by non-MeshDrop process"
        exit 1
    }
}

if (-not $backendAlreadyRunning -and (Test-PortOpen $TcpPort)) {
    Write-Host "[ERROR] TCP port $TcpPort is already in use by another process." -ForegroundColor Red
    Write-Host "Please free port $TcpPort or specify another port using -TcpPort." -ForegroundColor Yellow
    Log-Launcher "ERROR: TCP port $TcpPort in use"
    exit 1
}

if (Test-PortOpen $FrontendPort) {
    Write-Host "[ERROR] Frontend port $FrontendPort is already in use by another application." -ForegroundColor Red
    Write-Host "Please free port $FrontendPort or specify another port using -FrontendPort." -ForegroundColor Yellow
    Log-Launcher "ERROR: Frontend port $FrontendPort in use"
    exit 1
}

# ------------------------------------------------------------------------------
# 5. Build Verification (Backend & Frontend)
# ------------------------------------------------------------------------------
$mainClassFile = Join-Path $BackendRoot "out\com\meshdrop\Main.class"
if (-not (Test-Path $mainClassFile)) {
    Write-Host "`n[BACKEND] Compiled classes not found. Building Java backend..." -ForegroundColor Yellow
    $buildScript = Join-Path $BackendRoot "scripts\build.ps1"
    & powershell -ExecutionPolicy Bypass -File $buildScript
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $mainClassFile)) {
        Write-Host "[ERROR] Failed to compile Java backend. See error output above." -ForegroundColor Red
        Log-Launcher "ERROR: Java compilation failed"
        exit 1
    }
}

if ($Production) {
    $distIndex = Join-Path $FrontendRoot "dist\index.html"
    if (-not (Test-Path $distIndex)) {
        Write-Host "`n[FRONTEND] Production build not found. Building frontend assets..." -ForegroundColor Yellow
        Push-Location $FrontendRoot
        try {
            & $npmCmd.Source run build
            if ($LASTEXITCODE -ne 0) {
                Write-Host "[ERROR] Frontend production build failed." -ForegroundColor Red
                exit 1
            }
        } finally {
            Pop-Location
        }
    }
}

# ------------------------------------------------------------------------------
# 6. Child Process State Tracking & Cleanup
# ------------------------------------------------------------------------------
$script:BackendProcess = $null
$script:FrontendProcess = $null

function Stop-LauncherChildren {
    Write-Host "`n[MESHDROP] Shutting down..." -ForegroundColor Yellow
    Log-Launcher "Initiating graceful shutdown"

    if ($script:FrontendProcess -and -not $script:FrontendProcess.HasExited) {
        Write-Host "[FRONTEND] Stopping frontend (PID: $($script:FrontendProcess.Id))..." -ForegroundColor Yellow
        try {
            taskkill /PID $script:FrontendProcess.Id /T /F *>$null
            $script:FrontendProcess.WaitForExit(3000) | Out-Null
        } catch {}
        Log-Launcher "Stopped frontend process PID $($script:FrontendProcess.Id)"
    }

    if ($script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        Write-Host "[BACKEND] Stopping backend (PID: $($script:BackendProcess.Id))..." -ForegroundColor Yellow
        try {
            taskkill /PID $script:BackendProcess.Id /T /F *>$null
            $script:BackendProcess.WaitForExit(3000) | Out-Null
        } catch {}
        Log-Launcher "Stopped backend process PID $($script:BackendProcess.Id)"
    }

    Write-Host "[MESHDROP] Shutdown complete." -ForegroundColor Green
    Log-Launcher "Shutdown complete"
}

# ------------------------------------------------------------------------------
# 7. Start Java Backend
# ------------------------------------------------------------------------------
Write-Host "`n[2/4] Starting MeshDrop backend..." -ForegroundColor Yellow

if (-not $backendAlreadyRunning) {
    $javaArgs = @(
        "-cp", "out",
        "com.meshdrop.Main",
        "--name", "PC-Local",
        "--tcp-port", "$TcpPort",
        "--udp-port", "$UdpPort",
        "--api-port", "$BackendPort",
        "--no-cli"
    )

    $script:BackendProcess = Start-Process -FilePath "java" -ArgumentList $javaArgs `
        -WorkingDirectory $BackendRoot `
        -RedirectStandardOutput $BackendLog `
        -RedirectStandardError $BackendErrLog `
        -PassThru -NoNewWindow

    if (-not $script:BackendProcess) {
        Write-Host "[ERROR] Could not launch Java process." -ForegroundColor Red
        exit 1
    }

    Write-Host "  [OK] Backend started (PID: $($script:BackendProcess.Id))" -ForegroundColor Green
    Log-Launcher "Backend process launched (PID: $($script:BackendProcess.Id))"
}

# Wait for backend readiness via HTTP status endpoint
$backendReady = $false
$backendStatusUrl = "http://127.0.0.1:$BackendPort/api/status"
$backendTimeout = (Get-Date).AddSeconds(20)

while ((Get-Date) -lt $backendTimeout) {
    if ($script:BackendProcess -and $script:BackendProcess.HasExited) {
        break
    }
    try {
        $statusJson = Invoke-RestMethod -Uri $backendStatusUrl -TimeoutSec 1 -ErrorAction Stop
        if ($statusJson -and $statusJson.running -eq $true) {
            $backendReady = $true
            break
        }
    } catch {
        Start-Sleep -Milliseconds 250
    }
}

if (-not $backendReady) {
    Write-Host "[BACKEND] Failed to start. Process exited or failed readiness checks." -ForegroundColor Red
    if (Test-Path $BackendErrLog) {
        Write-Host "`nRecent backend stderr output ($BackendErrLog):" -ForegroundColor Yellow
        Get-Content $BackendErrLog -Tail 15 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    } elseif (Test-Path $BackendLog) {
        Write-Host "`nRecent backend stdout output ($BackendLog):" -ForegroundColor Yellow
        Get-Content $BackendLog -Tail 15 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    }
    Stop-LauncherChildren
    exit 1
}
Write-Host "  [OK] Backend ready (http://localhost:$BackendPort)" -ForegroundColor Green
Log-Launcher "Backend readiness verified"

# ------------------------------------------------------------------------------
# 8. Start Frontend UI Server
# ------------------------------------------------------------------------------
Write-Host "`n[3/4] Starting MeshDrop frontend..." -ForegroundColor Yellow

$frontendArgs = if ($Production) {
    @("run", "preview", "--", "--port", "$FrontendPort", "--host")
} else {
    @("run", "dev", "--", "--port", "$FrontendPort", "--host")
}

$script:FrontendProcess = Start-Process -FilePath $npmCmd.Source -ArgumentList $frontendArgs `
    -WorkingDirectory $FrontendRoot `
    -RedirectStandardOutput $FrontendLog `
    -RedirectStandardError $FrontendErrLog `
    -PassThru -NoNewWindow

if (-not $script:FrontendProcess) {
    Write-Host "[ERROR] Could not start frontend server." -ForegroundColor Red
    Stop-LauncherChildren
    exit 1
}

Write-Host "  [OK] Frontend started (PID: $($script:FrontendProcess.Id))" -ForegroundColor Green
Log-Launcher "Frontend process launched (PID: $($script:FrontendProcess.Id))"

# Wait for frontend readiness
$frontendReady = $false
$frontendUrl = "http://localhost:$FrontendPort"
$frontendTimeout = (Get-Date).AddSeconds(25)

while ((Get-Date) -lt $frontendTimeout) {
    if ($script:FrontendProcess.HasExited) {
        break
    }
    try {
        $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$FrontendPort" -UseBasicParsing -TimeoutSec 1 -ErrorAction Stop
        if ($resp.StatusCode -eq 200) {
            $frontendReady = $true
            break
        }
    } catch {
        Start-Sleep -Milliseconds 300
    }
}

if (-not $frontendReady) {
    Write-Host "[FRONTEND] Failed to start. Process exited or did not respond on port $FrontendPort." -ForegroundColor Red
    if (Test-Path $FrontendErrLog) {
        Write-Host "`nRecent frontend stderr output ($FrontendErrLog):" -ForegroundColor Yellow
        Get-Content $FrontendErrLog -Tail 15 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    }
    Stop-LauncherChildren
    exit 1
}
Write-Host "  [OK] Frontend ready ($frontendUrl)" -ForegroundColor Green
Log-Launcher "Frontend readiness verified"

# ------------------------------------------------------------------------------
# 9. Open Browser
# ------------------------------------------------------------------------------
Write-Host "`n[4/4] Opening MeshDrop..." -ForegroundColor Yellow
if (-not $NoBrowser) {
    try {
        Start-Process $frontendUrl
        Write-Host "  [OK] Browser opened" -ForegroundColor Green
        Log-Launcher "Browser opened to $frontendUrl"
    } catch {
        Write-Host "  [NOTE] Could not open browser automatically: $_" -ForegroundColor Yellow
    }
} else {
    Write-Host "  [NOTE] Browser auto-open skipped (-NoBrowser)." -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Cyan
Write-Host " MeshDrop is ready." -ForegroundColor Green
Write-Host " Backend : http://localhost:$BackendPort" -ForegroundColor White
Write-Host " Frontend: $frontendUrl" -ForegroundColor White
Write-Host " Mode    : $(if ($Production) { 'Production (Built assets)' } else { 'Development (Vite HMR)' })" -ForegroundColor Gray
Write-Host " Logs    : $LogsDir" -ForegroundColor DarkGray
Write-Host "----------------------------------------" -ForegroundColor Cyan
Write-Host "Press Ctrl+C to stop MeshDrop.`n" -ForegroundColor Yellow

# ------------------------------------------------------------------------------
# 10. Process Supervision Loop & Clean Exit
# ------------------------------------------------------------------------------
try {
    while ($true) {
        Start-Sleep -Seconds 1

        if ($script:BackendProcess -and $script:BackendProcess.HasExited) {
            Write-Host "`n[ERROR] MeshDrop backend exited unexpectedly (Exit code: $($script:BackendProcess.ExitCode))." -ForegroundColor Red
            Write-Host "Check logs at: $BackendLog" -ForegroundColor Yellow
            Log-Launcher "Backend died unexpectedly with exit code $($script:BackendProcess.ExitCode)"
            break
        }

        if ($script:FrontendProcess -and $script:FrontendProcess.HasExited) {
            Write-Host "`n[ERROR] MeshDrop frontend exited unexpectedly (Exit code: $($script:FrontendProcess.ExitCode))." -ForegroundColor Red
            Write-Host "Check logs at: $FrontendLog" -ForegroundColor Yellow
            Log-Launcher "Frontend died unexpectedly with exit code $($script:FrontendProcess.ExitCode)"
            break
        }
    }
} finally {
    Stop-LauncherChildren
}

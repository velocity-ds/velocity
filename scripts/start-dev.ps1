# Restarts the Velocity development stack on this machine: the Spring Boot backend on its configured port
# and the Vite console dev server. Anything already listening on either port is stopped first, so the
# script is safe to run repeatedly. Each server runs in its own console window so its log stays visible.
#
#   powershell -ExecutionPolicy Bypass -File scripts\start-dev.ps1
#
# Ports: the backend reads SERVER_PORT from .env (default 8090); the console always uses 5173 and
# proxies /api/ to VELOCITY_DEV_BACKEND_PORT (default 8090). Requires JDK 25, Node 22+, and MongoDB.

[CmdletBinding()]
param(
    [int]$BackendPort = 0,
    [int]$ConsolePort = 5173,
    [int]$StartupTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Read-EnvValue([string]$name) {
    $file = Join-Path $root '.env'
    if (-not (Test-Path $file)) {
        return $null
    }
    foreach ($line in Get-Content $file) {
        if ($line -match "^\s*$name\s*=\s*(.*?)\s*$") {
            return $Matches[1].Trim('"', "'")
        }
    }
    return $null
}

if ($BackendPort -eq 0) {
    $configured = Read-EnvValue 'SERVER_PORT'
    $BackendPort = if ($configured) { [int]$configured } else { 8090 }
}

function Get-ListenerIds([int]$port) {
    $ids = @()
    try {
        $ids = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess -Unique
    } catch {
        $ids = @()
    }
    return @($ids | Where-Object { $_ -and $_ -ne 0 })
}

function Stop-Listener([string]$label, [int]$port) {
    $ids = Get-ListenerIds $port
    if ($ids.Count -eq 0) {
        Write-Host "$label port $port is free."
        return
    }
    foreach ($id in $ids) {
        $process = Get-Process -Id $id -ErrorAction SilentlyContinue
        if (-not $process) {
            continue
        }
        Write-Host "Stopping $label (pid $id, $($process.ProcessName)) on port $port."
        # The listener sits under a chain of launcher processes (the Maven wrapper JVM, npm, the console
        # window this script opened). Stop the chain as well so no dead window lingers, but stop at the
        # first ancestor that is not one of those launchers, such as an IDE or an interactive shell.
        $current = Get-CimInstance Win32_Process -Filter "ProcessId = $id" -ErrorAction SilentlyContinue
        $chain = @($id)
        for ($depth = 0; $depth -lt 6 -and $current -and $current.ParentProcessId; $depth++) {
            $parent = Get-CimInstance Win32_Process -Filter "ProcessId = $($current.ParentProcessId)" -ErrorAction SilentlyContinue
            if (-not $parent -or -not $parent.CommandLine) {
                break
            }
            if ($parent.CommandLine -notmatch 'plexus-classworlds|spring-boot:run|mvnw|npm|vite|title Velocity (backend|console)') {
                break
            }
            $chain += $parent.ProcessId
            $current = $parent
        }
        foreach ($member in $chain) {
            Stop-Process -Id $member -Force -ErrorAction SilentlyContinue
        }
    }
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-ListenerIds $port).Count -gt 0 -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if ((Get-ListenerIds $port).Count -gt 0) {
        throw "Port $port is still in use after stopping its listener; stop it by hand and rerun."
    }
}

function Wait-ForUrl([string]$label, [string]$url, [int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                Write-Host "$label is up at $url"
                return $true
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    Write-Warning "$label did not answer at $url within $timeoutSeconds seconds; check its window."
    return $false
}

Write-Host "Velocity development stack from $root"
Stop-Listener 'Backend' $BackendPort
Stop-Listener 'Console' $ConsolePort
# Windows this script opened earlier whose server has already exited are no longer reachable by port.
Get-CimInstance Win32_Process -Filter "Name = 'cmd.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'title Velocity (backend|console)' } |
    ForEach-Object {
        Write-Host "Closing stale launcher window (pid $($_.ProcessId))."
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }

if (-not (Test-Path (Join-Path $root 'frontend\node_modules'))) {
    Write-Host 'Installing console dependencies (npm ci)...'
    Push-Location (Join-Path $root 'frontend')
    try {
        npm ci
    } finally {
        Pop-Location
    }
}

# The backend runs without -Pfrontend: the console is served by Vite below, not from the JAR.
Write-Host "Starting backend on port $BackendPort (Maven spring-boot:run, compiles changed modules first)..."
Start-Process -FilePath 'cmd.exe' -WorkingDirectory $root -ArgumentList @(
    '/k', 'title Velocity backend && .\mvnw.cmd -pl velocity-modules/velocity-app -am spring-boot:run'
)

Write-Host "Starting console on port $ConsolePort (Vite dev server)..."
$env:VELOCITY_DEV_BACKEND_PORT = "$BackendPort"
Start-Process -FilePath 'cmd.exe' -WorkingDirectory $root -ArgumentList @(
    '/k', 'title Velocity console && npm run dev --prefix frontend'
)

$consoleUp = Wait-ForUrl 'Console' "http://127.0.0.1:$ConsolePort/" 120
$backendUp = Wait-ForUrl 'Backend' "http://localhost:$BackendPort/velocity/hello" $StartupTimeoutSeconds
if ($consoleUp -and $backendUp) {
    Write-Host "Open http://localhost:$ConsolePort/ for the console."
}

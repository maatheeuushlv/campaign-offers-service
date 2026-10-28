# Shared helpers. Dot-source it:  . "$PSScriptRoot\lib.ps1"
# Works in Windows PowerShell 5.1 and PowerShell 7. Keep this file ASCII.

$ErrorActionPreference = 'Stop'
$script:Root    = Split-Path -Parent $PSScriptRoot
$script:Cluster = 'reports-lab'
$script:Ns      = 'reports-lab'
$script:Gateway = 'http://localhost:8080'

function Step([string]$Message) {
    Write-Host "==> $Message" -ForegroundColor Cyan
}

# Run a native command and stop the script if it fails.
function Run([string]$Exe, [string[]]$CmdArgs) {
    & $Exe @CmdArgs
    if ($LASTEXITCODE -ne 0) {
        throw "$Exe $($CmdArgs -join ' ') failed (exit code $LASTEXITCODE)"
    }
}

function Assert-Tools {
    $missing = @()
    foreach ($t in 'docker', 'kind', 'kubectl') {
        if (-not (Get-Command $t -ErrorAction SilentlyContinue)) { $missing += $t }
    }
    if ($missing.Count -gt 0) {
        throw ("Missing: " + ($missing -join ', ') + ". Install with:  winget install Docker.DockerDesktop Kubernetes.kind Kubernetes.kubectl")
    }
    $dockerOk = $true
    try {
        & docker info *> $null
        if ($LASTEXITCODE -ne 0) { $dockerOk = $false }
    } catch { $dockerOk = $false }
    if (-not $dockerOk) { throw 'Docker is installed but not running. Start Docker Desktop and try again.' }
}

function Use-Cluster {
    Run kubectl @('config', 'use-context', "kind-$script:Cluster")
}

function Wait-Rollout([string[]]$Deployments) {
    foreach ($d in $Deployments) {
        Run kubectl @('-n', $script:Ns, 'rollout', 'status', "deploy/$d", '--timeout=300s')
    }
}

function Clear-Redis {
    Step 'Flushing Redis (drops every cached view)'
    Run kubectl @('-n', $script:Ns, 'exec', 'deploy/redis', '--', 'redis-cli', 'FLUSHALL')
}

# Waits until the gateway answers through the kind port mapping (localhost:8080).
function Wait-Gateway {
    for ($i = 0; $i -lt 90; $i++) {
        try {
            $r = Invoke-RestMethod -Uri "$script:Gateway/dev/flags" -TimeoutSec 3
            return $r
        } catch { Start-Sleep -Seconds 2 }
    }
    throw "Gateway did not answer on $script:Gateway. Check: kubectl -n $script:Ns get pods"
}

function Show-Flags {
    $f = Wait-Gateway
    Write-Host ''
    Write-Host "Active flags: $($f.flags)" -ForegroundColor Yellow
}

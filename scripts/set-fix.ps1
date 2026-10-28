# Turns the individual fixes on/off to see what each one solves ALONE or in combination.
# Anything you do not pass keeps its current value.
#
#   .\scripts\set-fix.ps1 -Atomic true -AfterCommit false -Versioned false -Lock false   # only fix 1
#   .\scripts\set-fix.ps1 -Atomic true -Versioned true                                   # the recommended pair
#   .\scripts\set-fix.ps1 -WriteDelayMs 0 -LoadDelayMs 0                                 # realistic timings (bug becomes rare)
param(
    [ValidateSet('true', 'false')][string]$Atomic,        # fix 1: one conditional Mongo update instead of read-modify-write
    [ValidateSet('true', 'false')][string]$AfterCommit,   # fix 2: invalidate the cache after the commit (delete key)
    [ValidateSet('true', 'false')][string]$Versioned,     # fix 3: versioned cache key, version bumped after the commit
    [ValidateSet('true', 'false')][string]$Lock,          # fix 4: per-customer Redis lock around the write
    [int]$WriteDelayMs = -1,
    [int]$LoadDelayMs = -1
)
. "$PSScriptRoot\lib.ps1"
Assert-Tools
Use-Cluster

$data = [ordered]@{}
if ($Atomic)      { $data['FIX_ATOMIC_WRITE'] = $Atomic }
if ($AfterCommit) { $data['FIX_INVALIDATE_AFTER_COMMIT'] = $AfterCommit }
if ($Versioned)   { $data['FIX_VERSIONED_CACHE'] = $Versioned }
if ($Lock)        { $data['FIX_LOCK'] = $Lock }
if ($WriteDelayMs -ge 0) { $data['SIM_WRITE_DELAY_MS'] = "$WriteDelayMs" }
if ($LoadDelayMs -ge 0)  { $data['SIM_LOAD_DELAY_MS'] = "$LoadDelayMs" }
if ($data.Count -eq 0) { throw 'Nothing to change. Pass at least one of -Atomic -AfterCommit -Versioned -Lock -WriteDelayMs -LoadDelayMs.' }

$patchFile = Join-Path ([System.IO.Path]::GetTempPath()) 'lab-config-patch.json'
$json = @{ data = $data } | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($patchFile, $json, (New-Object System.Text.UTF8Encoding($false)))

Step "Patching lab-config: $json"
Run kubectl @('-n', $script:Ns, 'patch', 'configmap', 'lab-config', '--type', 'merge', '--patch-file', $patchFile)
Remove-Item $patchFile -Force -ErrorAction SilentlyContinue

Run kubectl @('-n', $script:Ns, 'rollout', 'restart', 'deploy/reports-gateway', 'deploy/campaign-offers-service')
Wait-Rollout @('reports-gateway', 'campaign-offers-service')
Clear-Redis
Show-Flags

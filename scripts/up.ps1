# Creates the kind cluster, builds and loads the images, deploys a scenario and seeds MongoDB.
#   .\scripts\up.ps1                      -> PROBLEM scenario
#   .\scripts\up.ps1 -Scenario fixed      -> FIXED scenario
#   .\scripts\up.ps1 -SkipBuild           -> reuse images already loaded in kind
param(
    [ValidateSet('problem', 'fixed')][string]$Scenario = 'problem',
    [switch]$SkipBuild
)
. "$PSScriptRoot\lib.ps1"
Assert-Tools

$exists = (& kind get clusters 2>$null) -contains $script:Cluster
if (-not $exists) {
    Step "Creating kind cluster '$script:Cluster' (ports 8080/8081/8082 mapped to localhost)"
    Run kind @('create', 'cluster', '--config', (Join-Path $script:Root 'k8s\kind-config.yaml'))
} else {
    Step "kind cluster '$script:Cluster' already exists"
}
Use-Cluster

if (-not $SkipBuild) { & "$PSScriptRoot\build-images.ps1" }

Step "Deploying scenario '$Scenario'"
Run kubectl @('apply', '-k', (Join-Path $script:Root "k8s\overlays\$Scenario"))
Wait-Rollout @('mongo', 'redis')

& "$PSScriptRoot\seed.ps1"

Wait-Rollout @('reports-gateway', 'reports-service', 'campaign-offers-service')
Run kubectl @('-n', $script:Ns, 'get', 'pods', '-o', 'wide')
Show-Flags

Write-Host ''
Write-Host 'Ready.' -ForegroundColor Green
Write-Host '  endpoint 1 + 4 : http://localhost:8080   (reports-gateway, 2 pods)'
Write-Host '  endpoint 2     : http://localhost:8081   (reports-service, 2 pods, no Redis)'
Write-Host '  endpoint 3     : http://localhost:8082   (campaign-offers-service, Redis cache)'
Write-Host '  next           : .\scripts\run-test.ps1   (burst test)   |   .\scripts\demo.ps1   (step by step)'

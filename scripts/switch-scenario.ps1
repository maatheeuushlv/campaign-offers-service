# Switches the running cluster between the two scenarios without rebuilding anything.
#   .\scripts\switch-scenario.ps1 -Scenario fixed
param(
    [Parameter(Mandatory = $true)][ValidateSet('problem', 'fixed')][string]$Scenario
)
. "$PSScriptRoot\lib.ps1"
Assert-Tools
Use-Cluster

Step "Applying scenario '$Scenario'"
Run kubectl @('apply', '-k', (Join-Path $script:Root "k8s\overlays\$Scenario"))
Step 'Restarting the pods so they read the new configuration'
Run kubectl @('-n', $script:Ns, 'rollout', 'restart', 'deploy/reports-gateway', 'deploy/campaign-offers-service')
Wait-Rollout @('reports-gateway', 'campaign-offers-service')
Clear-Redis
Show-Flags

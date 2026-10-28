# Deletes the kind cluster (and with it everything the lab created). Docker images stay on your machine.
. "$PSScriptRoot\lib.ps1"
Run kind @('delete', 'cluster', '--name', $script:Cluster)

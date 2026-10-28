# Runs the burst test INSIDE the cluster (a Kubernetes Job), so every request is balanced over both gateway pods.
#   .\scripts\run-test.ps1
#   .\scripts\run-test.ps1 -Customers 30 -Rounds 5 -Sequence "insert,remove"
#   .\scripts\run-test.ps1 -StaggerMs 5        # clicks 5 ms apart: shows the ordering problem no server-side fix can solve
param(
    [int]$Customers = 20,
    [int]$Rounds = 3,
    [int]$StaggerMs = 100,
    [int]$PollMs = 40,
    [int]$SettleMs = 1500,
    [int]$Parallelism = 8,
    [string]$Sequence = 'insert,remove,insert'
)
. "$PSScriptRoot\lib.ps1"
Assert-Tools
Use-Cluster
Wait-Gateway | Out-Null

$yaml = @"
apiVersion: batch/v1
kind: Job
metadata:
  name: lab-tester
  namespace: $script:Ns
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: tester
          image: reports-lab/lab-tester:1.0.0
          imagePullPolicy: IfNotPresent
          env:
            - { name: GATEWAY_URL,  value: "http://reports-gateway:8080" }
            - { name: CUSTOMERS,    value: "$Customers" }
            - { name: ROUNDS,       value: "$Rounds" }
            - { name: STAGGER_MS,   value: "$StaggerMs" }
            - { name: POLL_MS,      value: "$PollMs" }
            - { name: SETTLE_MS,    value: "$SettleMs" }
            - { name: PARALLELISM,  value: "$Parallelism" }
            - { name: SEQUENCE,     value: "$Sequence" }
          resources:
            requests: { cpu: 100m, memory: 128Mi }
            limits: { memory: 512Mi }
"@

Run kubectl @('-n', $script:Ns, 'delete', 'job', 'lab-tester', '--ignore-not-found')
Step 'Starting the tester Job'
$yaml | & kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw 'could not create the tester job' }

$total = $Customers * $Rounds * ($SettleMs + 3000) / 1000 / $Parallelism
Step ("Waiting for the Job (roughly {0:N0}s)..." -f $total)
$done = $false
for ($i = 0; $i -lt 900 -and -not $done; $i++) {
    $job = (& kubectl -n $script:Ns get job lab-tester -o json) | ConvertFrom-Json
    if ($job.status.succeeded -ge 1 -or $job.status.failed -ge 1) { $done = $true } else { Start-Sleep -Seconds 2 }
}
Write-Host ''
Run kubectl @('-n', $script:Ns, 'logs', 'job/lab-tester')
if (-not $done) { Write-Host 'Timed out waiting for the Job; logs above are partial.' -ForegroundColor Yellow }

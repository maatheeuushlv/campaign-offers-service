# Step-by-step manual walk through the four endpoints for ONE customer (default user002).
#   .\scripts\demo.ps1
#   .\scripts\demo.ps1 -Logon user001      # odd users already hold an EXPIRED offer in the cart (preSelected=true)
param([string]$Logon = 'user002')
. "$PSScriptRoot\lib.ps1"
$gw = $script:Gateway
$feature = 'Quadro societ' + [char]0xE1 + 'rio'           # "Quadro societario" with the accent, ASCII-safe in this file

$customer = Invoke-RestMethod "$gw/dev/customers?limit=50" | Where-Object { $_.logon -eq $Logon }
if (-not $customer) { throw "customer $Logon not found. Did the seed run?" }
$doc = $customer.document
$token = Invoke-RestMethod "$gw/dev/token?logon=$Logon&cnpj=$doc"
$auth = @{ Authorization = "Bearer $token" }

function Show([string]$title, $obj) {
    Write-Host ''
    Write-Host "--- $title" -ForegroundColor Cyan
    $obj | ConvertTo-Json -Depth 10
}
function Post-Change([string]$op, $offer) {
    $param = if ($op -eq 'insert') { 'insert_recommendation' } else { 'remove_recommendation' }
    $uri = "$gw/reports/logon/$Logon/preferences?$param=$($offer.id)&features=$([uri]::EscapeDataString($feature))&reportName=$($offer.report)"
    $r = Invoke-WebRequest -Method Post -Uri $uri -Headers $auth -UseBasicParsing
    Write-Host ("POST {0,-6} -> {1}  X-Reason={2}  pod={3}" -f $op, $r.StatusCode, $r.Headers['X-Reason'], $r.Headers['X-Served-By']) -ForegroundColor Yellow
}
function Get-Offer($prefs) {
    foreach ($rep in $prefs.reports) { foreach ($rec in $rep.recommendations) { foreach ($f in $rec.features) {
        if ($f.featureName -eq $feature) { return @{ id = $rec.recommendationId; report = $rep.reportName } } } } }
}

Invoke-RestMethod -Method Post -Uri "$gw/dev/reset/$Logon/$doc" | Out-Null
Write-Host "Customer $Logon  CNPJ $doc"

Show 'ENDPOINT 2  GET /reports/logon/{logon}/document/{cnpj}  (direct on :8081, no cache: always Mongo)' (Invoke-RestMethod -Uri "http://localhost:8081/reports/logon/$Logon/document/$doc" -Headers $auth)
Show 'ENDPOINT 3  GET /campaign-offers/logon/{logon}/document/{cnpj}  (direct on :8082, cached 12h in Redis, cart-aware)' (Invoke-RestMethod -Uri "http://localhost:8082/campaign-offers/logon/$Logon/document/$doc" -Headers $auth)

$prefs = Invoke-RestMethod -Uri "$gw/reports/logon/$Logon/preferences" -Headers $auth
Show 'ENDPOINT 1  GET /reports/logon/{logon}/preferences  (cart 1 = recommendations, cart 2 = features)' $prefs
$offer = Get-Offer $prefs

Post-Change 'insert' $offer
Show 'ENDPOINT 1 after insert: the feature moved from recommendations to features (preSelected=true)' (Invoke-RestMethod -Uri "$gw/reports/logon/$Logon/preferences" -Headers $auth)

Post-Change 'insert' $offer   # inserting again is rejected: only preSelected=false features can be inserted
Post-Change 'remove' $offer
Show 'ENDPOINT 1 after remove: back to recommendations (still valid today)' (Invoke-RestMethod -Uri "$gw/reports/logon/$Logon/preferences" -Headers $auth)

Write-Host ''
Write-Host 'Mongo truth (bypasses every cache):' -ForegroundColor Cyan
Invoke-RestMethod -Uri "$gw/dev/truth/$Logon/$doc" | ConvertTo-Json -Depth 5

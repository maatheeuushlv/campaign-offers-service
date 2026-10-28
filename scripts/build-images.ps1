# Builds the four images with Docker (Maven + JDK 21 run inside the build stage) and loads them into the kind cluster.
. "$PSScriptRoot\lib.ps1"

$modules = 'reports-gateway', 'reports-service', 'campaign-offers-service', 'lab-tester'

foreach ($m in $modules) {
    $image = "reports-lab/${m}:1.0.0"

    Step "docker build $image  (first build downloads Maven dependencies, later ones reuse the cache)"
    Run docker @('build', '--build-arg', "MODULE=$m", '-t', $image, $script:Root)

    Step "kind load $image"
    & kind load docker-image $image --name $script:Cluster
    if ($LASTEXITCODE -ne 0) {
        # Docker Desktop with the containerd image store sometimes breaks 'kind load docker-image'. Archive route works.
        Write-Host '   falling back to docker save + kind load image-archive' -ForegroundColor Yellow
        $tar = Join-Path ([System.IO.Path]::GetTempPath()) "$m.tar"
        Run docker @('save', '-o', $tar, $image)
        Run kind @('load', 'image-archive', $tar, '--name', $script:Cluster)
        Remove-Item $tar -Force -ErrorAction SilentlyContinue
    }
}
Write-Host 'Images built and loaded.' -ForegroundColor Green

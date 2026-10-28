# reports-lab: reproduce and fix the "sticky cart" race (Spring Boot 4, MongoDB, Redis, Docker, kind)

Read `docs/ROOT-CAUSE-AND-SOLUTIONS.md` for the analysis. This file is about running the lab.

## What runs

| Component | Replicas | Endpoints |
|-----------|----------|-----------|
| `reports-gateway` | 2 | 1 `GET /reports/logon/{logon}/preferences` and 4 `POST /reports/logon/{logon}/preferences` (port 8080) |
| `reports-service` | 2 | 2 `GET /reports/logon/{logon}/document/{customerDocument}`: reads MongoDB directly, **no Redis, no cache** (port 8081) |
| `campaign-offers-service` | 1 | 3 `GET /campaign-offers/logon/{logon}/document/{customerDocument}`: eligible recommendations, **cached 12 h in Redis** (port 8082) |
| MongoDB, Redis | 1 each | Mongo is shared by the three services; Redis is used by campaign-offers-service (cache) and by the gateway (invalidation, optional lock) |

All endpoints take `Authorization: Bearer <JWT>` (HS256, `sub` = logon, claim `cnpj` = company). The logon and CNPJ in the path must match the token, otherwise 403.

Endpoint 1 returns, per report, `features` (the inserted cart, from endpoint 2; `preSelected=true` when it has a `campaignOfferId`) and `recommendations` (the eligible cart, exactly as endpoint 3 returns it: offers valid today minus the features already inserted, cached 12 h). Insert moves a feature from `recommendations` to `features`; remove moves it back while the offer is valid; an expired offer that is still inserted stays in `features` until removed.

The POST invalidates the endpoint-3 cache (Redis key `offers:view:<logon>|<cnpj>`).

Endpoint 4: `?insert_recommendation=<offerId>` or `?remove_recommendation=<offerId>`, plus `features=<name>[,<name>...]` and `reportName=<name>`. `200` applied, `204` not applied (reason in the `X-Reason` header).

## Requirements (Windows)

Docker Desktop (running), `kind`, `kubectl`. PowerShell 5.1 or 7. Maven and the JDK are **not** needed: they run inside the Docker build.

```powershell
winget install Docker.DockerDesktop Kubernetes.kind Kubernetes.kubectl
```

If PowerShell blocks the scripts: `Set-ExecutionPolicy -Scope Process Bypass`.

## Quick start

```powershell
cd reports-lab
.\scripts\up.ps1                    # cluster + images + PROBLEM scenario + seed (first run: several minutes)
.\scripts\demo.ps1                  # one customer, step by step
.\scripts\run-test.ps1              # the click storm: expect STALE_VIEW and STUCK

.\scripts\switch-scenario.ps1 -Scenario fixed
.\scripts\run-test.ps1              # expect: no inconsistency

.\scripts\down.ps1                  # delete the cluster
```

## Evaluating each fix separately

```powershell
.\scripts\set-fix.ps1 -Atomic true  -AfterCommit false -Versioned false -Lock false
.\scripts\run-test.ps1 -Sequence "insert,remove"
.\scripts\set-fix.ps1 -Atomic true  -Versioned true
.\scripts\run-test.ps1 -Sequence "insert,remove,insert"
```

| Flag | Env var | What it does |
|------|---------|--------------|
| `-Atomic` | `FIX_ATOMIC_WRITE` | Validate and write in one conditional Mongo update |
| `-AfterCommit` | `FIX_INVALIDATE_AFTER_COMMIT` | Delete the cache key after the commit |
| `-Versioned` | `FIX_VERSIONED_CACHE` | Versioned cache key, version bumped after the commit |
| `-Lock` | `FIX_LOCK` | Per-customer Redis lock around the write |
| `-WriteDelayMs`, `-LoadDelayMs` | `SIM_WRITE_DELAY_MS`, `SIM_LOAD_DELAY_MS` | Artificial latency (default 300 / 60). Use 0 for realistic timings |

## Test data

50 customers `user001..user050` (valid CNPJs). Each has offer A (valid, `Quadro societário`, the one the test clicks), B (last day is today), C (expired yesterday) and D (starts in 3 days). Odd users already hold the expired offer C in the cart, so it shows with `preSelected=true`. Re-run `.\scripts\seed.ps1` after midnight so dates stay relative to today.

## By hand

```powershell
$tok = Invoke-RestMethod "http://localhost:8080/dev/token?logon=user002&cnpj=10000002000134"
curl.exe -H "Authorization: Bearer $tok" http://localhost:8080/reports/logon/user002/preferences
curl.exe -X POST -H "Authorization: Bearer $tok" "http://localhost:8080/reports/logon/user002/preferences?insert_recommendation=<offerId>&features=Quadro%20societ%C3%A1rio&reportName=RELATORIO_AVANCADO_PJ"
```

`/dev/*` (token, Mongo truth, reset, flags) exist only for the lab and have no authentication.

## Layout

```
lab-common/                shared JWT, DTOs, cache keys, flags
reports-gateway/           endpoints 1 and 4; PreferenceCommandService has the problem (naive) and fixed (atomic) paths
reports-service/           endpoint 2 (Mongo only)
campaign-offers-service/   endpoint 3 + Redis cache (OffersCache)
lab-tester/                the click-storm test (plain Java, runs as a Kubernetes Job)
k8s/                       kind config, base manifests, overlays problem/fixed, seed job
scripts/                   PowerShell: up, run-test, switch-scenario, set-fix, demo, down
```

## Troubleshooting

- `kubectl -n reports-lab get pods`, `kubectl -n reports-lab logs deploy/reports-gateway`.
- Build fails on the Spring Boot version: change `<version>` of the parent in `pom.xml` to any available 4.x release.
- `kind load docker-image` errors: the script falls back to `docker save` + `kind load image-archive`.
- Ports 8080 to 8082 busy: edit `hostPort` in `k8s/kind-config.yaml` and the URLs in `scripts/lib.ps1`.
- Memory: five JVMs plus Mongo and Redis need roughly 3 GB free for Docker Desktop.

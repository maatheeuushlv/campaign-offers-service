# (Re)creates the demo data in MongoDB (50 customers, user001..user050) and flushes the Redis cache.
. "$PSScriptRoot\lib.ps1"
Use-Cluster

Step 'Seeding MongoDB'
Run kubectl @('-n', $script:Ns, 'delete', 'job', 'mongo-seed', '--ignore-not-found')
Run kubectl @('apply', '-k', (Join-Path $script:Root 'k8s\seed'))
Run kubectl @('-n', $script:Ns, 'wait', '--for=condition=complete', 'job/mongo-seed', '--timeout=240s')
Run kubectl @('-n', $script:Ns, 'logs', 'job/mongo-seed')
Clear-Redis

# Root cause and solutions: the cart that "sticks"

## 1. Symptom

With 2 gateway pods, a quick `insert -> remove -> insert` leaves the two carts of endpoint 1 wrong: the feature shows in both carts, or in neither, while MongoDB says otherwise. Adding it back (or sometimes removing it) is then rejected. The screen and the database disagree until something else invalidates the cache, up to 12 h.

Where the cache lives: endpoint 3 (`campaign-offers-service`) returns the eligible recommendations (valid today and not yet inserted) and caches them 12 h in Redis. Endpoint 2 (`reports-service`) has no cache. So the part of endpoint 1 that goes stale is `recommendations` (cart 1); `features` (cart 2) is always fresh from Mongo.

## 2. How the lab reproduces it (problem scenario)

`POST /reports/logon/{logon}/preferences` (`PreferenceCommandService.naive`) does:

| # | Step | What is wrong with it |
|---|------|----------------------|
| 1 | Insert: asks endpoint 3 whether the offer is eligible (valid today and not inserted). Remove: asks endpoint 2 whether it is in the cart | For insert the answer comes from the **12 h Redis cache**: the write is validated against a copy, not the source of truth. |
| 2 | Reads the whole customer document from Mongo | Another pod may be doing the same at the same moment. |
| 3 | Changes the list in memory | |
| 4 | **Deletes the Redis key** | Invalidation happens **before** the new data exists in Mongo. |
| 5 | (latency: GC pause, slow Mongo, network) | The race window. The lab makes it 300 ms so it fits on a laptop. |
| 6 | **Overwrites the whole document** in Mongo | Last writer wins, no condition, no version. |

Meanwhile any reader (the front re-reading the cart, another tab, the mobile app) calls endpoint 3. After step 4 the key is missing, so the reader goes to Mongo, which still has the **old** data, and writes it into Redis with a 12 h TTL. Step 6 then commits, and nothing invalidates the cache again.

```
POST(pod A)                         GET(any pod)                          Redis            Mongo
  validate (reads cache) ------------------------------------------------> offers v0         v0
  read doc  ------------------------------------------------------------------------------>  v0
  DEL key  ---------------------------------------------------------------> (empty)
                                    miss -> read Mongo ----------------------------------->  v0
  ...300 ms...                      fill cache with v0 ------------------> offers v0 (12 h)
  overwrite doc ------------------------------------------------------------------------->  v1
                                    next GET: HIT -> shows v0     <-- stale, and it validates the next POST
```

Because step 1 of the **next** insert also reads that stale eligibility, it is answered `204 OFFER_NOT_ELIGIBLE` while Mongo says the feature is not inserted (the item is in neither cart and cannot be added back), or it is accepted while the feature is already inserted (the item shows in both carts). Removal is validated on the uncached endpoint 2, so it keeps working, but it re-triggers the stale cache because the invalidation is still done before the write. It is also why the effect is worse with 2 pods: the requests of one click storm are executed in parallel by different JVMs, so steps 2 to 6 of different requests interleave.

## 3. Causes, ordered by how common they are in real systems

This ranking is engineering judgment from experience with this class of bug, not measured data. The "Lab" column is what the lab shows.

| # | Cause | How common | Lab evidence |
|---|-------|-----------|--------------|
| 1 | **Cache-aside race**: invalidate before commit, or a slow reader fills the cache after the invalidation, with a long TTL and no versioning | Very common. The classic "stale cache" bug | Stale view: every round in the problem scenario |
| 2 | **Validating a write against a cached read model** (breaks read-your-writes) | Very common, usually hidden behind "just call the other service" | Insert rejected or accepted wrongly because of the stale eligibility |
| 3 | **Read-modify-write without a condition** on a shared document (lost update) | Very common with several pods | `LOST_UPDATE` / `DUPLICATE` with some click sequences |
| 4 | **Reordering of concurrent requests** from the same user (no client sequencing, no idempotency key, no precondition) | Common | Only visible with a very small `-StaggerMs`; see section 6 |
| 5 | **Dual write without atomicity** (Mongo commit succeeded, Redis invalidation failed or was lost) | Less frequent, but nasty because nothing logs an error the user sees | Not injected; the code logs it (`cache invalidation failed AFTER commit`) |
| 6 | Local per-pod caches (Caffeine) invalidated only on the pod that served the POST | Common, but **not applicable here**: the cache is shared in Redis | n/a |

## 4. Solutions evaluated

| Solution | Fixes cause | Cost / downside | Verdict |
|----------|------------|-----------------|---------|
| **A. Atomic conditional write in Mongo** (`updateOne` with a filter such as "not already inserted" / "is inserted", plus `$push`/`$pull` and `$inc version`). The write validates against the source of truth. | 2, 3 | Small code change, no new infrastructure. Does **not** touch the cache. | **Do it.** Necessary, not sufficient. |
| **B. Invalidate after commit** (delete the key after the Mongo write) | 1 (partly) | One line. Still racy: a reader that read Mongo before the commit can fill the key after your `DEL`. | Improvement only; leaves a smaller window |
| **C. Versioned cache key** (`...:v{n}`, writer does `INCR` after the commit, readers read `n` first) | 1 (completely) | One extra Redis read per GET. Old keys expire by TTL. If the `INCR` is lost after a commit the cache stays stale (cause 5). | **Do it.** Closes the race, not just narrows it |
| **D. Per-customer distributed lock (Redis)** | 3 (serializes writers) | Adds latency, a failure mode (lock timeout, TTL shorter than the work) and does nothing for the reader race or for cause 4. | Not needed once A is in place. Available as `FIX_LOCK` |
| **E. Short TTL / no cache for this endpoint** | 1, 2 (hides them) | 12 h was a requirement; shortening trades load for staleness. | Mitigation, not a fix |
| **F. Write-through: the writer updates the cache with the new view** | 1 | Duplicates the view-building logic in the writer, still needs ordering. | Alternative to C if you can afford it |
| **G. Client sequencing / `If-Match` version / idempotency key** | 4 | Needs a front-end change. | The only real answer to cause 4 |
| **H. Outbox / CDC to invalidate from the change stream** | 5 | New moving parts. | Worth it only if cause 5 shows up in production |

Recommended minimum: **A + C**. This is the `fixed` overlay.

## 5. What the lab measures

`run-test.ps1` fires the click storm and, after it settles, compares three things: what the customer clicked last (intent), what Mongo holds (truth) and what endpoint 1 shows (view). Then it tries to flip the item.

| Flag | Meaning |
|------|---------|
| `LOST_UPDATE` | Mongo does not hold what was clicked last |
| `STALE_VIEW` | Endpoint 1 differs from Mongo: the item is in both carts or in neither |
| `STUCK` | The customer cannot flip the item: the POST answers 204 or has no effect |
| `DUPLICATE` | The same feature was stored twice |

Expected pattern (I checked it on a behavioural model of the Java logic with the same steps, delays and flags, not on the real stack, because this environment had no Docker or Kubernetes; please confirm the numbers on your machine):

| Configuration (`set-fix.ps1`) | Expected result |
|-------------------------------|-----------------|
| none (problem) | Almost every round `STALE_VIEW`; `LOST_UPDATE` with `insert,remove` |
| A only | `LOST_UPDATE`/`DUPLICATE`/`STUCK` gone, `STALE_VIEW` remains |
| B only | Looks fine for some click sequences, fails for `insert,remove` |
| A + B | Mostly fine, occasional `STALE_VIEW` (the residual race) |
| **A + C** | Clean in every sequence tried |

Try the sequences `insert`, `insert,remove` and `insert,remove,insert` (`-Sequence`). Different sequences expose different weaknesses, which is why one lucky run proves nothing.

## 6. What no server-side fix can do

If the front end fires `insert, remove, insert` without waiting for the answers, the requests can arrive in a different order than the clicks. A conditional write keeps Mongo **consistent** (each request is applied or rejected cleanly), but the final state is the order of arrival, not of the clicks. Run `.\scripts\run-test.ps1 -StaggerMs 5` on the fixed scenario to see `LOST_UPDATE` come back. The fix for this is on the client (disable the button until the answer arrives, or send a version or sequence number and reject stale ones: solution G).

## 7. Residual risks of the fixed scenario

- If Redis is down or the `INCR` fails right after the Mongo commit, the cache is stale until the TTL (cause 5). The code logs the failure; the remedy is a retry, an outbox, or a shorter TTL.
- Old versioned keys sit in Redis until their TTL expires. Harmless, but size Redis accordingly.
- The simulated delays (`SIM_WRITE_DELAY_MS`, `SIM_LOAD_DELAY_MS`) exist only to make a rare race frequent. Set both to 0 to see the realistic, much rarer, behaviour.

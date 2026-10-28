package lab.common;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Switches that turn the "problem" implementation into the "fixed" one, one solution at a time.
 * Problem scenario = everything false. Fixed scenario = atomic + after-commit + versioned cache.
 */
@Component
public class LabFlags {

    private final boolean atomicWrite;
    private final boolean invalidateAfterCommit;
    private final boolean versionedCache;
    private final boolean lock;
    private final long simWriteDelayMs;
    private final long simLoadDelayMs;
    private final Duration cacheTtl;

    public LabFlags(@Value("${lab.fix.atomic-write:false}") boolean atomicWrite,
                    @Value("${lab.fix.invalidate-after-commit:false}") boolean invalidateAfterCommit,
                    @Value("${lab.fix.versioned-cache:false}") boolean versionedCache,
                    @Value("${lab.fix.lock:false}") boolean lock,
                    @Value("${lab.sim.write-delay-ms:0}") long simWriteDelayMs,
                    @Value("${lab.sim.load-delay-ms:0}") long simLoadDelayMs,
                    @Value("${lab.cache.ttl-hours:12}") long cacheTtlHours) {
        this.atomicWrite = atomicWrite;
        this.invalidateAfterCommit = invalidateAfterCommit;
        this.versionedCache = versionedCache;
        this.lock = lock;
        this.simWriteDelayMs = simWriteDelayMs;
        this.simLoadDelayMs = simLoadDelayMs;
        this.cacheTtl = Duration.ofHours(cacheTtlHours);
    }

    /** Fix 1: validate and write in ONE conditional Mongo update instead of read-modify-write. */
    public boolean atomicWrite() {
        return atomicWrite;
    }

    /** Fix 2: invalidate the cache after the Mongo commit instead of before. */
    public boolean invalidateAfterCommit() {
        return invalidateAfterCommit;
    }

    /** Fix 3: cache key carries a version that the writer bumps after the commit. */
    public boolean versionedCache() {
        return versionedCache;
    }

    /** Fix 4 (optional): per customer distributed lock in Redis around the write. */
    public boolean lock() {
        return lock;
    }

    /** Artificial latency between "decide" and "commit" in the gateway (stands for slow Mongo, GC, network). */
    public long simWriteDelayMs() {
        return simWriteDelayMs;
    }

    /** Artificial latency between "read Mongo" and "put in cache" in campaign-offers-service. */
    public long simLoadDelayMs() {
        return simLoadDelayMs;
    }

    public Duration cacheTtl() {
        return cacheTtl;
    }

    public String describe() {
        return "atomicWrite=" + atomicWrite + " invalidateAfterCommit=" + invalidateAfterCommit
                + " versionedCache=" + versionedCache + " lock=" + lock
                + " simWriteDelayMs=" + simWriteDelayMs + " simLoadDelayMs=" + simLoadDelayMs
                + " cacheTtl=" + cacheTtl;
    }
}

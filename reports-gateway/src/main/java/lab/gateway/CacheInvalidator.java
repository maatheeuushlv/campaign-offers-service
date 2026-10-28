package lab.gateway;

import lab.common.Ids;
import lab.common.LabFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Invalidates the 12h endpoint-2 cache that lives in Redis (same keys as reports-service, see {@link Ids}). */
@Component
public class CacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidator.class);

    private final StringRedisTemplate redis;
    private final LabFlags flags;

    public CacheInvalidator(StringRedisTemplate redis, LabFlags flags) {
        this.redis = redis;
        this.flags = flags;
    }

    /** True when the invalidation must happen AFTER the Mongo commit (fix 2 or fix 3). */
    public boolean afterCommitMode() {
        return flags.versionedCache() || flags.invalidateAfterCommit();
    }

    /** Problem behaviour: drop the key BEFORE the write is committed. */
    public void deleteBeforeWrite(String logon, String document) {
        redis.delete(Ids.cachePlain(logon, document));
    }

    /** Fix behaviour: after the commit, either bump the version (fix 3) or delete the key (fix 2). */
    public void afterCommit(String logon, String document) {
        try {
            if (flags.versionedCache()) {
                redis.opsForValue().increment(Ids.cacheVersion(logon, document));
            } else {
                redis.delete(Ids.cachePlain(logon, document));
            }
        } catch (RuntimeException e) {
            // Mongo already committed. If this fails the cache stays stale until the TTL: see the document, "residual risk".
            log.error("cache invalidation failed AFTER commit for {}", Ids.customer(logon, document), e);
        }
    }

    /** Dev reset: clears the plain key and bumps the version so every key family is invalidated. */
    public void resetAll(String logon, String document) {
        redis.delete(Ids.cachePlain(logon, document));
        redis.opsForValue().increment(Ids.cacheVersion(logon, document));
    }
}

package lab.campaign;

import java.time.Clock;
import java.time.LocalDate;
import lab.common.Dto.RecommendationsView;
import lab.common.Ids;
import lab.common.LabFlags;
import lab.common.Sim;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-through cache (Redis, 12h TTL) in front of {@link OffersRepository}: GET key -> miss -> read Mongo -> SET key.
 * Nothing here knows whether a writer committed in the meantime; that is what the problem scenario exploits.
 *
 * <p>With {@code lab.fix.versioned-cache=true} the key contains a version that the writer bumps after its commit,
 * so a slow reader can only fill an OLD key that nobody reads any more.
 */
@Component
public class OffersCache {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record Result(RecommendationsView view, boolean hit) {
    }

    private final StringRedisTemplate redis;
    private final OffersRepository repository;
    private final LabFlags flags;
    private final Clock clock;

    public OffersCache(StringRedisTemplate redis, OffersRepository repository, LabFlags flags, Clock clock) {
        this.redis = redis;
        this.repository = repository;
        this.flags = flags;
        this.clock = clock;
    }

    public Result get(String logon, String document) {
        String key = flags.versionedCache()
                ? Ids.cacheVersioned(logon, document, currentVersion(logon, document))
                : Ids.cachePlain(logon, document);

        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return new Result(MAPPER.readValue(cached, RecommendationsView.class), true);
        }

        RecommendationsView view = repository.eligible(logon, document, LocalDate.now(clock).toString()); // (1) read Mongo
        Sim.sleep(flags.simLoadDelayMs());                                                             //     slow query / GC / network
        redis.opsForValue().set(key, MAPPER.writeValueAsString(view), flags.cacheTtl());                // (2) fill cache
        return new Result(view, false);
    }

    private long currentVersion(String logon, String document) {
        String v = redis.opsForValue().get(Ids.cacheVersion(logon, document));
        return v == null ? 0L : Long.parseLong(v);
    }
}

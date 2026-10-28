package lab.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab.common.Dto.OfferFeature;
import lab.common.Dto.Recommendation;
import lab.common.Dto.ReportView;
import lab.common.Dto.ReportsView;
import lab.common.Ids;
import lab.common.LabFlags;
import lab.common.Sim;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoint 4 (insert_recommendation / remove_recommendation).
 *
 * <p>Two implementations of the same business rule live side by side so the difference is easy to read:
 * {@link #naive} is the PROBLEM scenario and {@link #atomic} is the FIXED one. {@code lab.fix.atomic-write}
 * chooses; the cache flags ({@link LabFlags}) are honoured by both so each fix can be evaluated alone.
 */
@Service
public class PreferenceCommandService {

    public enum Action { INSERT, REMOVE }

    public record Command(Action action, String logon, String document, String offerId,
                          String reportName, List<String> featureNames) {
    }

    /** ok=true -> HTTP 200, ok=false -> HTTP 204 (+ X-Reason header with the cause). */
    public record Outcome(boolean ok, String reason) {
        static Outcome success() {
            return new Outcome(true, "APPLIED");
        }

        static Outcome fail(String reason) {
            return new Outcome(false, reason);
        }
    }

    private final DownstreamClient downstream;
    private final OfferStore offers;
    private final SelectionStore store;
    private final CacheInvalidator cache;
    private final DistributedLock lock;
    private final LabFlags flags;

    public PreferenceCommandService(DownstreamClient downstream, OfferStore offers, SelectionStore store,
                                    CacheInvalidator cache, DistributedLock lock, LabFlags flags) {
        this.downstream = downstream;
        this.offers = offers;
        this.store = store;
        this.cache = cache;
        this.lock = lock;
        this.flags = flags;
    }

    public Outcome execute(Command cmd, String authorization) {
        return flags.atomicWrite() ? atomic(cmd) : naive(cmd, authorization);
    }

    /** Offer lookup shared by both paths: only features of an offer valid today can be inserted. */
    private Outcome resolveInsert(Recommendation offer, Command cmd, List<OfferFeature> out) {
        if (offer == null) {
            return Outcome.fail("OFFER_NOT_ELIGIBLE");
        }
        if (!offer.reportName().equals(cmd.reportName())) {
            return Outcome.fail("REPORT_MISMATCH");
        }
        Map<String, OfferFeature> byName = new LinkedHashMap<>();
        offer.features().forEach(f -> byName.put(f.featureName(), f));
        for (String name : cmd.featureNames()) {
            OfferFeature f = byName.get(name);
            if (f == null) {
                return Outcome.fail("FEATURE_NOT_ELIGIBLE");
            }
            out.add(f);
        }
        return null;
    }

    // =====================================================================================================
    // PROBLEM: eligibility is checked against the CACHED endpoint 3 (Redis, 12h), the whole customer document is
    //          read-modified-overwritten, and the cache is invalidated BEFORE the write is committed.
    // =====================================================================================================
    private Outcome naive(Command cmd, String authorization) {
        List<OfferFeature> features = new ArrayList<>();
        if (cmd.action() == Action.INSERT) {
            // (1) "Can this be inserted?" is answered by endpoint 3, i.e. by the cache. Eligible = valid today AND not inserted.
            Recommendation offer = downstream.offers(cmd.logon(), cmd.document(), authorization).view().recommendations().stream()
                    .filter(r -> r.recommendationId().equals(cmd.offerId()))
                    .findFirst().orElse(null);
            Outcome rejected = resolveInsert(offer, cmd, features);
            if (rejected != null) {
                return rejected;
            }
        } else {
            // Removal is allowed even after exhibitionEndDate. Endpoint 2 is uncached, so this check is accurate...
            ReportsView view = downstream.reports(cmd.logon(), cmd.document(), authorization);
            Set<String> inCart = new HashSet<>();
            for (ReportView r : view.reports()) {
                r.features().stream().filter(f -> f.campaignOfferId() != null)
                        .forEach(f -> inCart.add(PreferencesAssembler.key(r.reportName(), f.campaignOfferId(), f.featureName())));
            }
            for (String name : cmd.featureNames()) {
                if (!inCart.contains(PreferencesAssembler.key(cmd.reportName(), cmd.offerId(), name))) {
                    return Outcome.fail("NOT_IN_CART");
                }
                features.add(new OfferFeature(null, name));
            }
        }

        // (2) read the whole customer document, change it in memory
        Document doc = store.find(cmd.logon(), cmd.document());
        if (doc == null) {
            doc = store.newDocument(cmd.logon(), cmd.document());
        }
        List<Document> selections = doc.getList("selections", Document.class);
        if (selections == null) {
            selections = new ArrayList<>();
        }
        Set<String> names = new LinkedHashSet<>();
        features.forEach(f -> names.add(f.featureName()));
        if (cmd.action() == Action.INSERT) {
            for (OfferFeature f : features) {
                selections.add(SelectionStore.selection(cmd.offerId(), cmd.reportName(), f));
            }
        } else {
            selections.removeIf(s -> cmd.offerId().equals(s.getString("campaignOfferId"))
                    && cmd.reportName().equals(s.getString("reportName"))
                    && names.contains(s.getString("featureName")));
        }
        doc.put("selections", selections);

        // (3) invalidate BEFORE the write (unless a cache fix is enabled), then write
        boolean after = cache.afterCommitMode();
        if (!after) {
            cache.deleteBeforeWrite(cmd.logon(), cmd.document());
        }
        Sim.sleep(flags.simWriteDelayMs());
        store.replace(doc);                                   // overwrite the whole document: last writer wins
        if (after) {
            cache.afterCommit(cmd.logon(), cmd.document());
        }
        return Outcome.success();
    }

    // =====================================================================================================
    // FIXED: the source of truth validates and writes in ONE conditional update; the cache is invalidated
    //        after the commit (delete or version bump). Optional per-customer Redis lock.
    // =====================================================================================================
    private Outcome atomic(Command cmd) {
        List<OfferFeature> features = new ArrayList<>();
        if (cmd.action() == Action.INSERT) {
            // Static facts (offer exists, window contains today, features) come from the source of truth, never from the cache.
            // "Not inserted yet" is enforced by the conditional update itself.
            Outcome rejected = resolveInsert(offers.findValidToday(cmd.logon(), cmd.document(), cmd.offerId()), cmd, features);
            if (rejected != null) {
                return rejected;
            }
        } else {
            cmd.featureNames().forEach(n -> features.add(new OfferFeature(null, n)));
        }
        String lockKey = Ids.lock(cmd.logon(), cmd.document());
        String token = null;
        if (flags.lock()) {
            token = lock.acquire(lockKey, Duration.ofSeconds(10), Duration.ofSeconds(5));
            if (token == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "could not acquire customer lock, retry");
            }
        }
        try {
            boolean after = cache.afterCommitMode();
            if (!after) {
                cache.deleteBeforeWrite(cmd.logon(), cmd.document());
            }
            Sim.sleep(flags.simWriteDelayMs());               // same artificial latency as the problem path
            boolean changed;
            if (cmd.action() == Action.INSERT) {
                changed = store.atomicInsert(cmd.logon(), cmd.document(), cmd.offerId(), cmd.reportName(), features);
            } else {
                List<String> names = new ArrayList<>();
                features.forEach(f -> names.add(f.featureName()));
                changed = store.atomicRemove(cmd.logon(), cmd.document(), cmd.offerId(), cmd.reportName(), names);
            }
            if (!changed) {
                return Outcome.fail(cmd.action() == Action.INSERT ? "ALREADY_IN_CART" : "NOT_IN_CART");
            }
            if (after) {
                cache.afterCommit(cmd.logon(), cmd.document());
            }
            return Outcome.success();
        } finally {
            if (token != null) {
                lock.release(lockKey, token);
            }
        }
    }
}

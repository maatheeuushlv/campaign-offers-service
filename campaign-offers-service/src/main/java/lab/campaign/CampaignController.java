package lab.campaign;

import lab.common.Dto.RecommendationsView;
import lab.common.TokenGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CampaignController {

    private final TokenGuard guard;
    private final OffersCache cache;

    public CampaignController(TokenGuard guard, OffersCache cache) {
        this.guard = guard;
        this.cache = cache;
    }

    /**
     * Endpoint 3, cached 12h in Redis. A recommendation is eligible when exhibitionStartDate &lt;= today &lt;= exhibitionEndDate
     * and it is not already inserted in the customer's cart.
     */
    @GetMapping("/campaign-offers/logon/{logon}/document/{customerDocument}")
    public ResponseEntity<RecommendationsView> recommendations(@PathVariable("logon") String logon,
                                                               @PathVariable("customerDocument") String customerDocument,
                                                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        guard.require(authorization, logon, customerDocument);
        OffersCache.Result r = cache.get(logon, customerDocument);
        return ResponseEntity.ok().header("X-Cache", r.hit() ? "HIT" : "MISS").body(r.view());
    }
}

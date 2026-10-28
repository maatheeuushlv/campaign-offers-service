package lab.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lab.common.JwtService;
import lab.common.LabFlags;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LAB ONLY (lab.dev-endpoints=true). Token minting, the Mongo "truth", reset and the active flags.
 * No authentication on purpose: never deploy this anywhere real.
 */
@RestController
@ConditionalOnProperty(name = "lab.dev-endpoints", havingValue = "true")
public class DevController {

    private final JwtService jwt;
    private final SelectionStore store;
    private final CacheInvalidator cache;
    private final LabFlags flags;

    public DevController(JwtService jwt, SelectionStore store, CacheInvalidator cache, LabFlags flags) {
        this.jwt = jwt;
        this.store = store;
        this.cache = cache;
        this.flags = flags;
    }

    @GetMapping(value = "/dev/token", produces = "text/plain")
    public String token(@RequestParam("logon") String logon, @RequestParam("cnpj") String cnpj) {
        return jwt.issue(logon, cnpj, Duration.ofHours(2));
    }

    @GetMapping("/dev/customers")
    public List<Map<String, String>> customers(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return store.customers(limit);
    }

    /** What MongoDB really says, bypassing every cache. */
    @GetMapping("/dev/truth/{logon}/{document}")
    public Map<String, Object> truth(@PathVariable("logon") String logon, @PathVariable("document") String document) {
        return store.truth(logon, document);
    }

    @PostMapping("/dev/reset/{logon}/{document}")
    public Map<String, String> reset(@PathVariable("logon") String logon, @PathVariable("document") String document) {
        store.reset(logon, document);
        cache.resetAll(logon, document);
        return Map.of("status", "reset");
    }

    @GetMapping("/dev/flags")
    public Map<String, String> flags() {
        return Map.of("flags", flags.describe());
    }
}

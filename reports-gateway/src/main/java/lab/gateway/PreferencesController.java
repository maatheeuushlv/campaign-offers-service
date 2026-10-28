package lab.gateway;

import java.util.ArrayList;
import java.util.List;
import lab.common.Claims;
import lab.common.Dto.PreferencesView;
import lab.common.Dto.ReportsView;
import lab.common.TokenGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PreferencesController {

    private final TokenGuard guard;
    private final DownstreamClient downstream;
    private final PreferencesAssembler assembler;
    private final PreferenceCommandService commands;

    public PreferencesController(TokenGuard guard, DownstreamClient downstream, PreferencesAssembler assembler,
                                 PreferenceCommandService commands) {
        this.guard = guard;
        this.downstream = downstream;
        this.assembler = assembler;
        this.commands = commands;
    }

    /** Endpoint 1. The CNPJ comes from the token and is forwarded to endpoints 2 and 3. */
    @GetMapping("/reports/logon/{logon}/preferences")
    public ResponseEntity<PreferencesView> preferences(@PathVariable("logon") String logon,
                                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        Claims claims = guard.require(authorization, logon, null);
        ReportsView reports = downstream.reports(logon, claims.document(), authorization);          // endpoint 2: Mongo, no cache
        DownstreamClient.OffersResult offers = downstream.offers(logon, claims.document(), authorization); // endpoint 3: Redis 12h
        PreferencesView body = assembler.assemble(reports, offers.view());
        return ResponseEntity.ok()
                .header("X-Offers-Cache", String.valueOf(offers.cache()))
                .body(body);
    }

    /**
     * Endpoint 4. Exactly one of insert_recommendation / remove_recommendation.
     * 200 = applied, 204 = not applied (the cause is in the X-Reason header).
     */
    @PostMapping("/reports/logon/{logon}/preferences")
    public ResponseEntity<Void> change(@PathVariable("logon") String logon,
                                       @RequestParam(name = "insert_recommendation", required = false) String insert,
                                       @RequestParam(name = "remove_recommendation", required = false) String remove,
                                       @RequestParam("features") String features,
                                       @RequestParam("reportName") String reportName,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        Claims claims = guard.require(authorization, logon, null);
        if ((insert == null) == (remove == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "send exactly one of insert_recommendation or remove_recommendation");
        }
        List<String> names = new ArrayList<>();
        for (String n : features.split(",")) {
            if (!n.isBlank()) {
                names.add(n.trim());
            }
        }
        if (names.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "features must have at least one featureName");
        }
        PreferenceCommandService.Command cmd = new PreferenceCommandService.Command(
                insert != null ? PreferenceCommandService.Action.INSERT : PreferenceCommandService.Action.REMOVE,
                logon, claims.document(), insert != null ? insert : remove, reportName, names);

        PreferenceCommandService.Outcome outcome = commands.execute(cmd, authorization);
        return outcome.ok()
                ? ResponseEntity.ok().header("X-Reason", outcome.reason()).build()
                : ResponseEntity.noContent().header("X-Reason", outcome.reason()).build();
    }
}

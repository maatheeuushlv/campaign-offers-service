package lab.reports;

import lab.common.Dto.ReportsView;
import lab.common.TokenGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint 2. No cache and no Redis: always reads MongoDB (base features + inserted features). */
@RestController
public class ReportsController {

    private final TokenGuard guard;
    private final ReportsRepository repository;

    public ReportsController(TokenGuard guard, ReportsRepository repository) {
        this.guard = guard;
        this.repository = repository;
    }

    @GetMapping("/reports/logon/{logon}/document/{customerDocument}")
    public ReportsView reports(@PathVariable("logon") String logon,
                               @PathVariable("customerDocument") String customerDocument,
                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        guard.require(authorization, logon, customerDocument);
        return repository.load(logon, customerDocument);
    }
}

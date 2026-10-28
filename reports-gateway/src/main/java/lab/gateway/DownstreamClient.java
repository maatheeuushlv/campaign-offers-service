package lab.gateway;

import java.net.http.HttpClient;
import java.time.Duration;
import lab.common.Dto.RecommendationsView;
import lab.common.Dto.ReportsView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** HTTP calls to endpoint 2 (reports-service, no cache) and endpoint 3 (campaign-offers-service, Redis cache). The token is forwarded. */
@Component
public class DownstreamClient {

    public record OffersResult(RecommendationsView view, String cache) {
    }

    private final RestClient reports;
    private final RestClient campaign;

    public DownstreamClient(@Value("${lab.reports-service-url}") String reportsUrl,
                            @Value("${lab.campaign-service-url}") String campaignUrl) {
        this.reports = client(reportsUrl);
        this.campaign = client(campaignUrl);
    }

    private static RestClient client(String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public ReportsView reports(String logon, String document, String authorization) {
        try {
            return reports.get()
                    .uri("/reports/logon/{logon}/document/{document}", logon, document)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(ReportsView.class);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "reports-service failed: " + e.getMessage());
        }
    }

    public OffersResult offers(String logon, String document, String authorization) {
        try {
            ResponseEntity<RecommendationsView> r = campaign.get()
                    .uri("/campaign-offers/logon/{logon}/document/{document}", logon, document)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .toEntity(RecommendationsView.class);
            return new OffersResult(r.getBody(), r.getHeaders().getFirst("X-Cache"));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "campaign-offers-service failed: " + e.getMessage());
        }
    }
}

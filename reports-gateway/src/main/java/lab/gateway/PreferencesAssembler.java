package lab.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.common.Dto.PrefFeature;
import lab.common.Dto.PrefRecommendation;
import lab.common.Dto.PrefReport;
import lab.common.Dto.PreferencesView;
import lab.common.Dto.Recommendation;
import lab.common.Dto.RecommendationsView;
import lab.common.Dto.ReportView;
import lab.common.Dto.ReportsView;
import org.springframework.stereotype.Component;

/**
 * Builds the endpoint-1 response from endpoints 2 and 3.
 *
 * <ul>
 *   <li><b>features</b> ("cart 2", inserted): everything from endpoint 2; preSelected = it has a campaignOfferId.
 *       Features inserted from an offer stay here after the offer expires, until the customer removes them.</li>
 *   <li><b>recommendations</b> ("cart 1", eligible): exactly what endpoint 3 returns (offers valid today minus the
 *       features already inserted; Redis-cached 12h). After removal the feature goes back here, until exhibitionEndDate
 *       passes. The gateway does NOT filter it again, so a stale endpoint-3 cache is visible to the customer.</li>
 * </ul>
 */
@Component
public class PreferencesAssembler {

    private static final class Bucket {
        final List<PrefFeature> features = new ArrayList<>();
        final List<PrefRecommendation> recommendations = new ArrayList<>();
    }

    public PreferencesView assemble(ReportsView reportsView, RecommendationsView offersView) {
        Map<String, Bucket> byReport = new LinkedHashMap<>();

        for (ReportView report : reportsView.reports()) {
            Bucket bucket = byReport.computeIfAbsent(report.reportName(), k -> new Bucket());
            for (var f : report.features()) {
                boolean preSelected = f.campaignOfferId() != null;
                bucket.features.add(new PrefFeature(f.featureTransaction(), f.featureName(), f.campaignOfferId(), preSelected));
            }
        }

        for (Recommendation rec : offersView.recommendations()) {
            byReport.computeIfAbsent(rec.reportName(), k -> new Bucket()).recommendations
                    .add(new PrefRecommendation(rec.recommendationId(), rec.reportName(), rec.features()));
        }

        List<PrefReport> reports = new ArrayList<>();
        byReport.forEach((name, b) -> reports.add(new PrefReport(name, b.features, b.recommendations)));
        return new PreferencesView(reports);
    }

    public static String key(String reportName, String offerId, String featureName) {
        return reportName + "|" + offerId + "|" + featureName;
    }
}

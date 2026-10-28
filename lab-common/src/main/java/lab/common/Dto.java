package lab.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** JSON contracts shared by the three services. */
public final class Dto {

    private Dto() {
    }

    // ---- Endpoint 2 (reports-service) --------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeatureView(String featureTransaction, String featureName, String campaignOfferId) {
    }

    public record ReportView(String reportName, List<FeatureView> features) {
    }

    public record ReportsView(List<ReportView> reports) {
    }

    // ---- Endpoint 3 (campaign-offers-service) ------------------------------------------------

    public record OfferFeature(String featureTransaction, String featureName) {
    }

    public record Recommendation(String recommendationId,
                                 String reportName,
                                 String exhibitionStartDate,
                                 String exhibitionEndDate,
                                 List<OfferFeature> features) {
    }

    public record RecommendationsView(List<Recommendation> recommendations) {
    }

    // ---- Endpoint 1 (reports-gateway) --------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PrefFeature(String featureTransaction,
                              String featureName,
                              String campaignOfferId,
                              boolean preSelected) {
    }

    public record PrefRecommendation(String recommendationId,
                                     String reportName,
                                     List<OfferFeature> features) {
    }

    public record PrefReport(String reportName,
                             List<PrefFeature> features,
                             List<PrefRecommendation> recommendations) {
    }

    public record PreferencesView(List<PrefReport> reports) {
    }
}

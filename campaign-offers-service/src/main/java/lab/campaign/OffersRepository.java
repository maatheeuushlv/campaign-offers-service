package lab.campaign;

import com.mongodb.client.MongoCollection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lab.common.Dto.OfferFeature;
import lab.common.Dto.Recommendation;
import lab.common.Dto.RecommendationsView;
import lab.common.Ids;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Eligible recommendations ("cart 1"): offers whose exhibition window contains today, minus the features the customer
 * already inserted ("cart 2", collection customer_selections written by the gateway). Because the result depends on
 * the cart, whoever changes the cart must invalidate the cached copy.
 *
 * <p>Dates are ISO yyyy-MM-dd strings, so string comparison equals date comparison.
 */
@Repository
public class OffersRepository {

    private final MongoCollection<Document> offers;
    private final MongoCollection<Document> selections;

    public OffersRepository(MongoTemplate mongo) {
        this.offers = mongo.getCollection("campaign_offers");
        this.selections = mongo.getCollection("customer_selections");
    }

    public RecommendationsView eligible(String logon, String document, String today) {
        Set<String> inserted = new HashSet<>();
        Document sel = selections.find(new Document("_id", Ids.customer(logon, document))).first();
        if (sel != null && sel.getList("selections", Document.class) != null) {
            for (Document s : sel.getList("selections", Document.class)) {
                inserted.add(s.getString("campaignOfferId") + "|" + s.getString("reportName") + "|" + s.getString("featureName"));
            }
        }

        Document filter = new Document("logon", logon)
                .append("document", Ids.digits(document))
                .append("exhibitionStartDate", new Document("$lte", today))
                .append("exhibitionEndDate", new Document("$gte", today));

        List<Recommendation> result = new ArrayList<>();
        for (Document d : offers.find(filter).sort(new Document("exhibitionEndDate", 1).append("_id", 1))) {
            String id = d.getString("_id");
            String report = d.getString("reportName");
            List<OfferFeature> features = new ArrayList<>();
            List<Document> fs = d.getList("features", Document.class);
            if (fs != null) {
                for (Document f : fs) {
                    if (!inserted.contains(id + "|" + report + "|" + f.getString("featureName"))) {
                        features.add(new OfferFeature(f.getString("featureTransaction"), f.getString("featureName")));
                    }
                }
            }
            if (!features.isEmpty()) {
                result.add(new Recommendation(id, report, d.getString("exhibitionStartDate"),
                        d.getString("exhibitionEndDate"), features));
            }
        }
        return new RecommendationsView(result);
    }
}

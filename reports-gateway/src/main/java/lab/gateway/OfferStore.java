package lab.gateway;

import com.mongodb.client.MongoCollection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lab.common.Dto.OfferFeature;
import lab.common.Dto.Recommendation;
import lab.common.Ids;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Source of truth for "is this offer valid today?" (collection campaign_offers). Used by the FIXED write path so the
 * decision never depends on the cached endpoint 3.
 */
@Repository
public class OfferStore {

    private final MongoCollection<Document> offers;
    private final Clock clock;

    public OfferStore(MongoTemplate mongo, Clock clock) {
        this.offers = mongo.getCollection("campaign_offers");
        this.clock = clock;
    }

    /** The offer, or null when it does not exist for this customer or today is outside its exhibition window. */
    public Recommendation findValidToday(String logon, String document, String offerId) {
        String today = LocalDate.now(clock).toString();
        Document d = offers.find(new Document("_id", offerId)
                .append("logon", logon)
                .append("document", Ids.digits(document))
                .append("exhibitionStartDate", new Document("$lte", today))
                .append("exhibitionEndDate", new Document("$gte", today))).first();
        if (d == null) {
            return null;
        }
        List<OfferFeature> features = new ArrayList<>();
        List<Document> fs = d.getList("features", Document.class);
        if (fs != null) {
            for (Document f : fs) {
                features.add(new OfferFeature(f.getString("featureTransaction"), f.getString("featureName")));
            }
        }
        return new Recommendation(d.getString("_id"), d.getString("reportName"), d.getString("exhibitionStartDate"),
                d.getString("exhibitionEndDate"), features);
    }
}

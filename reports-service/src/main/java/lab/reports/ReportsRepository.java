package lab.reports;

import com.mongodb.client.MongoCollection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.common.Dto.FeatureView;
import lab.common.Dto.ReportView;
import lab.common.Dto.ReportsView;
import lab.common.Ids;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Builds the endpoint-2 view from MongoDB:
 * base features of the customer's reports (report_catalog) + features the customer inserted from a
 * campaign offer (customer_selections, written by the gateway's POST), which carry campaignOfferId.
 */
@Repository
public class ReportsRepository {

    private final MongoCollection<Document> catalog;
    private final MongoCollection<Document> selections;

    public ReportsRepository(MongoTemplate mongo) {
        this.catalog = mongo.getCollection("report_catalog");
        this.selections = mongo.getCollection("customer_selections");
    }

    public ReportsView load(String logon, String document) {
        String id = Ids.customer(logon, document);
        Document cat = catalog.find(new Document("_id", id)).first();
        Document sel = selections.find(new Document("_id", id)).first();

        Map<String, List<FeatureView>> byReport = new LinkedHashMap<>();
        if (cat != null) {
            for (Document report : list(cat, "reports")) {
                List<FeatureView> features = byReport.computeIfAbsent(report.getString("reportName"), k -> new ArrayList<>());
                for (Document f : list(report, "features")) {
                    features.add(new FeatureView(f.getString("featureTransaction"), f.getString("featureName"), null));
                }
            }
        }
        if (sel != null) {
            for (Document s : list(sel, "selections")) {
                byReport.computeIfAbsent(s.getString("reportName"), k -> new ArrayList<>())
                        .add(new FeatureView(s.getString("featureTransaction"), s.getString("featureName"),
                                s.getString("campaignOfferId")));
            }
        }
        List<ReportView> reports = new ArrayList<>();
        byReport.forEach((name, features) -> reports.add(new ReportView(name, features)));
        return new ReportsView(reports);
    }

    private static List<Document> list(Document d, String key) {
        List<Document> l = d.getList(key, Document.class);
        return l == null ? List.of() : l;
    }
}

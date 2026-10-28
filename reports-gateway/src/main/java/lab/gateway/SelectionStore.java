package lab.gateway;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lab.common.Dto.OfferFeature;
import lab.common.Ids;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write model: collection {@code customer_selections}, one document per logon+CNPJ:
 * <pre>{ _id, logon, document, version, selections: [ {campaignOfferId, reportName, featureTransaction, featureName, insertedAt} ] }</pre>
 * Endpoint 2 (reports-service) reads the same collection to mark features with their campaignOfferId.
 */
@Repository
public class SelectionStore {

    private final MongoCollection<Document> selections;
    private final MongoCollection<Document> catalog;

    public SelectionStore(MongoTemplate mongo) {
        this.selections = mongo.getCollection("customer_selections");
        this.catalog = mongo.getCollection("report_catalog");
    }

    // ============================ PROBLEM path: read - modify - overwrite ============================

    public Document find(String logon, String document) {
        return selections.find(new Document("_id", Ids.customer(logon, document))).first();
    }

    public Document newDocument(String logon, String document) {
        return new Document("_id", Ids.customer(logon, document))
                .append("logon", logon).append("document", Ids.digits(document))
                .append("version", 0L).append("selections", new ArrayList<Document>());
    }

    /** Full-document overwrite (last writer wins). No version check, no condition: this is the bug. */
    public void replace(Document doc) {
        selections.replaceOne(new Document("_id", doc.get("_id")), doc, new ReplaceOptions().upsert(true));
    }

    public static Document selection(String offerId, String reportName, OfferFeature f) {
        return new Document("campaignOfferId", offerId)
                .append("reportName", reportName)
                .append("featureTransaction", f.featureTransaction())
                .append("featureName", f.featureName())
                .append("insertedAt", Instant.now().toString());
    }

    // ============================ FIXED path: one conditional update ============================

    /** Atomic insert. Returns false (and changes nothing) if ANY of the features is already inserted for that offer. */
    public boolean atomicInsert(String logon, String document, String offerId, String reportName, List<OfferFeature> features) {
        ensure(logon, document);
        List<Bson> conditions = new ArrayList<>();
        conditions.add(new Document("_id", Ids.customer(logon, document)));
        List<Document> toPush = new ArrayList<>();
        for (OfferFeature f : new LinkedHashSet<>(features)) {
            conditions.add(new Document("selections", new Document("$not", new Document("$elemMatch", match(offerId, reportName, f.featureName())))));
            toPush.add(selection(offerId, reportName, f));
        }
        Document update = new Document("$push", new Document("selections", new Document("$each", toPush)))
                .append("$inc", new Document("version", 1L));
        UpdateResult r = selections.updateOne(new Document("$and", conditions), update);
        return r.getModifiedCount() == 1;
    }

    /** Atomic remove. Returns false (and changes nothing) if ANY of the features is not currently inserted for that offer. */
    public boolean atomicRemove(String logon, String document, String offerId, String reportName, List<String> featureNames) {
        List<Bson> conditions = new ArrayList<>();
        conditions.add(new Document("_id", Ids.customer(logon, document)));
        Set<String> names = new LinkedHashSet<>(featureNames);
        for (String name : names) {
            conditions.add(new Document("selections", new Document("$elemMatch", match(offerId, reportName, name))));
        }
        Document pullCondition = new Document("campaignOfferId", offerId)
                .append("reportName", reportName)
                .append("featureName", new Document("$in", new ArrayList<>(names)));
        Document update = new Document("$pull", new Document("selections", pullCondition))
                .append("$inc", new Document("version", 1L));
        UpdateResult r = selections.updateOne(new Document("$and", conditions), update);
        return r.getModifiedCount() == 1;
    }

    private static Document match(String offerId, String reportName, String featureName) {
        return new Document("campaignOfferId", offerId).append("reportName", reportName).append("featureName", featureName);
    }

    /** Creates the customer document if missing (idempotent, safe under concurrency). */
    private void ensure(String logon, String document) {
        try {
            selections.updateOne(new Document("_id", Ids.customer(logon, document)),
                    new Document("$setOnInsert", new Document("logon", logon).append("document", Ids.digits(document))
                            .append("version", 0L).append("selections", new ArrayList<Document>())),
                    new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            if (e.getError().getCode() != 11000) {   // duplicate key = somebody else created it first, fine
                throw e;
            }
        }
    }

    // ============================ DEV helpers (truth, reset, customers) ============================

    public Map<String, Object> truth(String logon, String document) {
        Document d = find(logon, document);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", d == null ? 0L : d.get("version"));
        List<Map<String, String>> list = new ArrayList<>();
        if (d != null && d.getList("selections", Document.class) != null) {
            for (Document s : d.getList("selections", Document.class)) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("campaignOfferId", s.getString("campaignOfferId"));
                m.put("reportName", s.getString("reportName"));
                m.put("featureName", s.getString("featureName"));
                list.add(m);
            }
        }
        out.put("selections", list);
        return out;
    }

    /** Back to the seed baseline (seed stores it in seedSelections). Goes straight to Mongo, bypassing the POST path. */
    public void reset(String logon, String document) {
        Document d = find(logon, document);
        if (d == null) {
            return;
        }
        List<Document> seed = d.getList("seedSelections", Document.class);
        selections.updateOne(new Document("_id", d.get("_id")),
                new Document("$set", new Document("selections", seed == null ? new ArrayList<Document>() : seed)
                        .append("version", 0L)));
    }

    public List<Map<String, String>> customers(int limit) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Document d : catalog.find().sort(new Document("logon", 1)).limit(limit)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("logon", d.getString("logon"));
            m.put("document", d.getString("document"));
            out.add(m);
        }
        return out;
    }
}

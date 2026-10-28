package lab.common;

/** Identifier and Redis key conventions shared by every service (they must agree, the cache is shared). */
public final class Ids {

    private Ids() {
    }

    public static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /** Mongo _id of a customer document: logon|cnpj-digits. */
    public static String customer(String logon, String document) {
        return logon + "|" + digits(document);
    }

    /** Endpoint-3 cache entry (12h TTL), owned by campaign-offers-service. */
    public static String cachePlain(String logon, String document) {
        return "offers:view:" + customer(logon, document);
    }

    /** Version counter used by the "versioned cache key" fix. */
    public static String cacheVersion(String logon, String document) {
        return "offers:ver:" + customer(logon, document);
    }

    public static String cacheVersioned(String logon, String document, long version) {
        return cachePlain(logon, document) + ":v" + version;
    }

    public static String lock(String logon, String document) {
        return "lock:prefs:" + customer(logon, document);
    }
}

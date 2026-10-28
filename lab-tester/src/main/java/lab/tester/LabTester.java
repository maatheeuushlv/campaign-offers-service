package lab.tester;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reproduces the customer's click storm: insert -> remove -> insert fired a few milliseconds apart,
 * the front re-reading the cart (endpoint 1) after every answer, plus other readers (tabs, app, BFF) polling.
 *
 * <p>A new TCP connection is opened for every request, so Kubernetes spreads the calls over BOTH gateway pods.
 *
 * <p>After the burst has settled it compares three views of reality: what the user clicked last (intent),
 * what MongoDB really holds (truth) and what endpoint 1 shows (view), then checks the customer can still flip the item.
 */
public final class LabTester {

    // ---------------------------------------------------------------- configuration
    static final String GATEWAY = env("GATEWAY_URL", "http://localhost:8080");
    static final int CUSTOMERS = Integer.parseInt(env("CUSTOMERS", "20"));
    static final int ROUNDS = Integer.parseInt(env("ROUNDS", "3"));
    static final int PARALLELISM = Integer.parseInt(env("PARALLELISM", "8"));
    static final long STAGGER_MS = Long.parseLong(env("STAGGER_MS", "100"));
    static final long POLL_MS = Long.parseLong(env("POLL_MS", "40"));
    static final long SETTLE_MS = Long.parseLong(env("SETTLE_MS", "1500"));
    static final String SEQUENCE = env("SEQUENCE", "insert,remove,insert");
    static final String FEATURE = env("FEATURE", "Quadro societário");
    static final int SHOW_FAILURES = Integer.parseInt(env("SHOW_FAILURES", "3"));

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    // ---------------------------------------------------------------- tiny HTTP helper
    record Resp(int status, String body, HttpResponse<String> raw) {
        String header(String name) {
            return raw == null ? "-" : raw.headers().firstValue(name).orElse("-");
        }
    }

    /** New HttpClient (= new connection) per call so the Service load-balances every request independently. */
    static Resp call(String method, String url, String token) {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resp(-1, "interrupted", null);
        } catch (Exception e) {
            return new Resp(-1, String.valueOf(e), null);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- domain helpers
    record Customer(String logon, String document) {
    }

    record Offer(String id, String reportName) {
    }

    static Resp prefs(String token, String logon) {
        return call("GET", GATEWAY + "/reports/logon/" + logon + "/preferences", token);
    }

    /** What endpoint 1 shows for the offer: in the inserted cart (features, preSelected) and/or in the eligible cart (recommendations). */
    record View(boolean inCart, boolean eligible) {
        boolean coherentWith(boolean mongoHasIt) {
            return inCart == mongoHasIt && eligible == !mongoHasIt;   // exactly one of the two carts must hold it
        }

        @Override
        public String toString() {
            return inCart && eligible ? "BOTH_CARTS" : inCart ? "IN_CART" : eligible ? "ELIGIBLE" : "NEITHER_CART";
        }
    }

    static View view(Resp prefs, String offerId) {
        boolean inCart = false;
        boolean eligible = false;
        if (prefs.status() == 200) {
            for (Object r : Json.arr(Json.obj(Json.parse(prefs.body())).get("reports"))) {
                for (Object f : Json.arr(Json.obj(r).get("features"))) {
                    Map<String, Object> fm = Json.obj(f);
                    if (FEATURE.equals(fm.get("featureName")) && offerId.equals(fm.get("campaignOfferId"))
                            && Boolean.TRUE.equals(fm.get("preSelected"))) {
                        inCart = true;
                    }
                }
                for (Object rec : Json.arr(Json.obj(r).get("recommendations"))) {
                    if (offerId.equals(Json.obj(rec).get("recommendationId"))) {
                        for (Object f : Json.arr(Json.obj(rec).get("features"))) {
                            if (FEATURE.equals(Json.obj(f).get("featureName"))) {
                                eligible = true;
                            }
                        }
                    }
                }
            }
        }
        return new View(inCart, eligible);
    }

    static Offer findOffer(Resp prefs) {
        for (Object r : Json.arr(Json.obj(Json.parse(prefs.body())).get("reports"))) {
            for (Object rec : Json.arr(Json.obj(r).get("recommendations"))) {
                for (Object f : Json.arr(Json.obj(rec).get("features"))) {
                    if (FEATURE.equals(Json.obj(f).get("featureName"))) {
                        return new Offer(Json.str(Json.obj(rec).get("recommendationId")), Json.str(Json.obj(rec).get("reportName")));
                    }
                }
            }
        }
        return null;
    }

    /** How many times MongoDB holds this selection (0 = not in cart, 1 = in cart, >1 = duplicate). */
    static int truthCount(Customer c, String offerId) {
        Resp t = call("GET", GATEWAY + "/dev/truth/" + c.logon() + "/" + c.document(), null);
        int n = 0;
        for (Object s : Json.arr(Json.obj(Json.parse(t.body())).get("selections"))) {
            Map<String, Object> m = Json.obj(s);
            if (offerId.equals(m.get("campaignOfferId")) && FEATURE.equals(m.get("featureName"))) {
                n++;
            }
        }
        return n;
    }

    static Resp post(String token, String logon, String op, Offer offer) {
        String param = op.equals("insert") ? "insert_recommendation" : "remove_recommendation";
        return call("POST", GATEWAY + "/reports/logon/" + logon + "/preferences?" + param + "=" + offer.id()
                + "&features=" + enc(FEATURE) + "&reportName=" + enc(offer.reportName()), token);
    }

    // ---------------------------------------------------------------- one round = one customer, one burst
    static final class Round {
        final String logon;
        final long t0 = System.nanoTime();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> flags = new ArrayList<>();
        String error;

        Round(String logon) {
            this.logon = logon;
        }

        void ev(String text) {
            events.add(String.format("  +%4dms  %s", (System.nanoTime() - t0) / 1_000_000, text));
        }
    }

    static final Map<String, AtomicInteger> POSTS_BY_POD = new ConcurrentHashMap<>();

    static Round runRound(Customer c) {
        Round rd = new Round(c.logon());
        call("POST", GATEWAY + "/dev/reset/" + c.logon() + "/" + c.document(), null);
        Resp tk = call("GET", GATEWAY + "/dev/token?logon=" + enc(c.logon()) + "&cnpj=" + enc(c.document()), null);
        if (tk.status() != 200) {
            rd.error = "cannot mint token: " + tk.status() + " " + tk.body();
            return rd;
        }
        String token = tk.body().trim();
        Offer offer = null;
        Resp first = prefs(token, c.logon());
        if (first.status() == 200) {
            offer = findOffer(first);
        }
        if (offer == null) {
            rd.error = "no recommendation with feature '" + FEATURE + "' (GET " + first.status() + ")";
            return rd;
        }
        final Offer o = offer;
        String[] ops = SEQUENCE.split(",");

        AtomicBoolean polling = new AtomicBoolean(true);
        ExecutorService pool = Executors.newCachedThreadPool();
        Future<?> poller = pool.submit(() -> {        // other readers: tabs, mobile app, BFF prefetch...
            while (polling.get()) {
                prefs(token, c.logon());
                sleep(POLL_MS);
            }
        });
        List<Future<?>> burst = new ArrayList<>();
        for (int i = 0; i < ops.length; i++) {
            final int idx = i;
            final String op = ops[i].trim();
            burst.add(pool.submit(() -> {
                sleep(idx * STAGGER_MS);
                Resp r = post(token, c.logon(), op, o);
                POSTS_BY_POD.computeIfAbsent(r.header("X-Served-By"), k -> new AtomicInteger()).incrementAndGet();
                rd.ev(String.format("POST %-6s -> %d %-18s pod=%s", op, r.status(), r.header("X-Reason"), r.header("X-Served-By")));
                Resp g = prefs(token, c.logon());          // the front re-reads the cart after every answer
                rd.ev(String.format("GET  cart  -> %d shows=%-12s offers-cache=%-4s pod=%s", g.status(),
                        view(g, o.id()), g.header("X-Offers-Cache"), g.header("X-Served-By")));
            }));
        }
        try {
            for (Future<?> f : burst) {
                f.get(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            rd.error = "burst failed: " + e;
        }
        polling.set(false);
        try {
            poller.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort
        }
        pool.shutdown();

        sleep(SETTLE_MS);                                   // let everything in flight finish

        boolean expected = ops[ops.length - 1].trim().equals("insert");   // what the customer clicked last
        int truth = truthCount(c, o.id());
        Resp v = prefs(token, c.logon());
        View view = view(v, o.id());
        rd.ev(String.format("SETTLED  intent=%s  mongo=%s  endpoint1=%s", expected ? "IN_CART" : "NOT_IN_CART",
                truth > 0 ? "IN_CART(x" + truth + ")" : "NOT_IN_CART", view));

        if ((truth > 0) != expected) {
            rd.flags.add("LOST_UPDATE");
        }
        if (truth > 1) {
            rd.flags.add("DUPLICATE");
        }
        if (!view.coherentWith(truth > 0)) {
            rd.flags.add("STALE_VIEW");
        }

        // Can the customer still flip the item? Do what is TRUE in Mongo: remove it if it is there, add it if it is not.
        String flip = truth > 0 ? "remove" : "insert";
        Resp fr = post(token, c.logon(), flip, o);
        int truth2 = truthCount(c, o.id());
        Resp v2 = prefs(token, c.logon());
        View view2 = view(v2, o.id());
        boolean wantIn = flip.equals("insert");
        rd.ev(String.format("FLIP %-6s -> %d %-18s mongo=%s endpoint1=%s", flip, fr.status(), fr.header("X-Reason"),
                truth2 > 0 ? "IN_CART" : "NOT_IN_CART", view2));
        if (fr.status() != 200 || (truth2 > 0) != wantIn || !view2.coherentWith(wantIn)) {
            rd.flags.add("STUCK");
        }
        return rd;
    }

    // ---------------------------------------------------------------- main
    public static void main(String[] args) throws Exception {
        System.out.println("=== lab-tester ===");
        System.out.printf("gateway=%s customers=%d rounds=%d stagger=%dms poll=%dms settle=%dms sequence=%s%n",
                GATEWAY, CUSTOMERS, ROUNDS, STAGGER_MS, POLL_MS, SETTLE_MS, SEQUENCE);
        Resp flags = call("GET", GATEWAY + "/dev/flags", null);
        System.out.println("gateway flags: " + flags.body());

        Resp list = call("GET", GATEWAY + "/dev/customers?limit=" + CUSTOMERS, null);
        if (list.status() != 200) {
            System.out.println("cannot list customers (" + list.status() + "): " + list.body() + "  -> is the seed done?");
            System.exit(2);
        }
        List<Customer> customers = new ArrayList<>();
        for (Object o : Json.arr(Json.parse(list.body()))) {
            customers.add(new Customer(Json.str(Json.obj(o).get("logon")), Json.str(Json.obj(o).get("document"))));
        }

        // One task per customer; that customer's rounds run one after the other (rounds of the SAME customer must never overlap).
        ExecutorService runner = Executors.newFixedThreadPool(PARALLELISM);
        List<Future<List<Round>>> futures = new ArrayList<>();
        for (Customer c : customers) {
            futures.add(runner.submit(() -> {
                List<Round> mine = new ArrayList<>();
                for (int r = 0; r < ROUNDS; r++) {
                    mine.add(runRound(c));
                }
                return mine;
            }));
        }
        List<Round> rounds = new ArrayList<>();
        for (Future<List<Round>> f : futures) {
            rounds.addAll(f.get());
        }
        runner.shutdown();

        Map<String, Integer> counts = new TreeMap<>();
        int ok = 0;
        int errors = 0;
        int shown = 0;
        for (Round rd : rounds) {
            if (rd.error != null) {
                errors++;
                System.out.println("ERROR " + rd.logon + ": " + rd.error);
                continue;
            }
            if (rd.flags.isEmpty()) {
                ok++;
                continue;
            }
            rd.flags.forEach(f -> counts.merge(f, 1, Integer::sum));
            if (shown++ < SHOW_FAILURES) {
                System.out.println("\n--- FAILED round of " + rd.logon + " " + rd.flags + " ---");
                List<String> evs = new ArrayList<>(rd.events);
                evs.forEach(System.out::println);
            }
        }

        System.out.println("\n=================== SUMMARY ===================");
        System.out.printf("rounds=%d  consistent=%d  broken=%d  errors=%d%n", rounds.size(), ok,
                rounds.size() - ok - errors, errors);
        System.out.println("  LOST_UPDATE : Mongo does not hold what the customer clicked last");
        System.out.println("  STALE_VIEW  : endpoint 1 disagrees with Mongo: the item is in both carts or in neither (the 12h endpoint-3 cache is wrong)");
        System.out.println("  STUCK       : the customer cannot add/remove the item any more (POST answers 204 or has no effect)");
        System.out.println("  DUPLICATE   : the same feature was inserted twice in Mongo");
        counts.forEach((k, v) -> System.out.printf("  %-12s %d round(s)%n", k, v));
        Map<String, Integer> pods = new TreeMap<>();
        POSTS_BY_POD.forEach((k, v) -> pods.put(k, v.get()));
        System.out.println("POSTs served per gateway pod: " + pods);
        System.out.println(rounds.size() - ok - errors == 0 && errors == 0
                ? "VERDICT: no inconsistency observed"
                : "VERDICT: inconsistencies observed (see above)");
    }
}

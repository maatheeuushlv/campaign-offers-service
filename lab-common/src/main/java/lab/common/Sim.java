package lab.common;

public final class Sim {

    private Sim() {
    }

    public static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Pod name inside Kubernetes (HOSTNAME), or "local". */
    public static String pod() {
        String h = System.getenv("HOSTNAME");
        return h == null || h.isBlank() ? "local" : h;
    }
}

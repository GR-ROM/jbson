package su.grinev;

import java.util.Properties;

/**
 * Build identity of the jbson jar — version and the incrementing build number baked in at publish time
 * (see {@code .build-number} / {@code jbson-build.properties}). Lets consumers log exactly which jbson
 * snapshot they are running, the same way MyVPN's image tag identifies its build.
 */
public final class JbsonBuild {

    public static final String VERSION;
    public static final String BUILD;

    static {
        String version = "unknown";
        String build = "0";
        try (var in = JbsonBuild.class.getResourceAsStream("/jbson-build.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                version = p.getProperty("version", version);
                build = p.getProperty("build", build);
            }
        } catch (Exception ignored) {
            // build info is best-effort — never fail because of it
        }
        VERSION = version;
        BUILD = build;
    }

    private JbsonBuild() {
    }

    /** e.g. {@code "jbson 0.8.0-SNAPSHOT (build 2)"} */
    public static String describe() {
        return "jbson " + VERSION + " (build " + BUILD + ")";
    }
}

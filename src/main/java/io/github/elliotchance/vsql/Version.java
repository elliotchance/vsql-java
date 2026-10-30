package io.github.elliotchance.vsql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The versions of the driver and libvsql. */
final class Version {
    private Version() {
    }

    /** The version of the jar (from its manifest), or "0.0.0" if it isn't known. */
    static final String DRIVER = driver();

    /**
     * The vsql release that libvsql is from (without the "v"), from
     * libvsql-version (see scripts/update-amalgamation.sh).
     */
    static final String LIBVSQL = libvsql();

    private static String driver() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v == null ? "0.0.0" : v;
    }

    private static String libvsql() {
        try (InputStream in = Version.class.getResourceAsStream("libvsql-version")) {
            if (in == null) {
                return "0.0.0";
            }
            String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return v.startsWith("v") ? v.substring(1) : v;
        } catch (IOException e) {
            return "0.0.0";
        }
    }

    /** A part of a version (0 for major, 1 for minor), or 0. */
    static int part(String version, int n) {
        String[] parts = version.split("[.-]");
        try {
            return n < parts.length ? Integer.parseInt(parts[n]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

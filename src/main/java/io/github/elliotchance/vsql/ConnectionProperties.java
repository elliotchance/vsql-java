package io.github.elliotchance.vsql;

import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The database and the connection properties of a URL and its Properties, see
 * {@link VsqlDriver}. A property in the URL replaces the same property in the
 * Properties. An unknown property in the URL is an error, but one in the
 * Properties is ignored (they often have others, such as user and password).
 */
final class ConnectionProperties {
    static final String AUTO_COMMIT = "autoCommit";
    static final String READ_ONLY = "readOnly";
    static final String TRANSACTION_ISOLATION = "transactionIsolation";

    private static final List<String> NAMES = List.of(AUTO_COMMIT, READ_ONLY, TRANSACTION_ISOLATION);
    private static final List<String> ISOLATION_NAMES =
            List.of("READ_UNCOMMITTED", "READ_COMMITTED", "REPEATABLE_READ", "SERIALIZABLE");
    private static final List<Integer> ISOLATION_LEVELS = List.of(Connection.TRANSACTION_READ_UNCOMMITTED,
            Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ,
            Connection.TRANSACTION_SERIALIZABLE);

    /** The database, which is the URL after the prefix, up to the first ';'. */
    final String database;

    /** The value of each property that's given. */
    private final Map<String, String> values = new HashMap<>();

    /** The URL must be a vsql URL. info can be null. */
    ConnectionProperties(String url, Properties info) throws SQLException {
        String rest = url.substring(VsqlDriver.PREFIX.length());
        int semicolon = rest.indexOf(';');
        database = semicolon < 0 ? rest : rest.substring(0, semicolon);

        if (info != null) {
            for (String name : NAMES) {
                Object value = info.get(name);
                if (value != null) {
                    values.put(name, value.toString());
                }
            }
        }

        if (semicolon >= 0) {
            for (String setting : rest.substring(semicolon + 1).split(";")) {
                if (setting.isEmpty()) {
                    continue;
                }
                int equals = setting.indexOf('=');
                String name = equals < 0 ? setting : setting.substring(0, equals);
                if (!NAMES.contains(name)) {
                    throw new SQLException("vsql: unknown connection property " + name + " in the URL (it can be "
                            + String.join(", ", NAMES) + ")", "08001");
                }
                if (equals < 0) {
                    throw new SQLException("vsql: the connection property " + name
                            + " in the URL has no value (such as " + name + "=...)", "08001");
                }
                values.put(name, setting.substring(equals + 1));
            }
        }

        // Each value is checked before connecting.
        autoCommit();
        readOnly();
        transactionIsolation();
    }

    /** True if the property is in the URL or the Properties. */
    boolean has(String name) {
        return values.containsKey(name);
    }

    boolean autoCommit() throws SQLException {
        return bool(AUTO_COMMIT, true);
    }

    boolean readOnly() throws SQLException {
        return bool(READ_ONLY, false);
    }

    /** A Connection.TRANSACTION_* level. */
    int transactionIsolation() throws SQLException {
        String value = values.get(TRANSACTION_ISOLATION);
        if (value == null) {
            return Connection.TRANSACTION_READ_COMMITTED;
        }
        int i = ISOLATION_NAMES.indexOf(value.trim().toUpperCase(Locale.ROOT));
        if (i < 0) {
            throw invalid(TRANSACTION_ISOLATION, value, String.join(", ", ISOLATION_NAMES));
        }
        return ISOLATION_LEVELS.get(i);
    }

    private boolean bool(String name, boolean defaultValue) throws SQLException {
        String value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(name, value, "true or false");
        };
    }

    private static SQLException invalid(String name, String value, String valid) {
        return new SQLException("vsql: invalid value for the connection property " + name + ": " + value
                + " (it can be " + valid + ")", "08001");
    }

    /** Describes each property, with its value (or its default). */
    DriverPropertyInfo[] info() {
        return new DriverPropertyInfo[] {
            info(AUTO_COMMIT, "true", "Each statement is committed when it finishes.", "true", "false"),
            info(READ_ONLY, "false", "Changing data is an error (SQLSTATE 25006).", "true", "false"),
            info(TRANSACTION_ISOLATION, "READ_COMMITTED", "The isolation level of a transaction.",
                    ISOLATION_NAMES.toArray(new String[0])),
        };
    }

    private DriverPropertyInfo info(String name, String defaultValue, String description, String... choices) {
        DriverPropertyInfo info = new DriverPropertyInfo(name, values.getOrDefault(name, defaultValue));
        info.description = description;
        info.required = false;
        info.choices = choices;
        return info;
    }
}

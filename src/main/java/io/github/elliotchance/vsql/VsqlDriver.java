package io.github.elliotchance.vsql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The vsql JDBC driver. Its URLs are {@code jdbc:vsql:PATH}, where PATH is the
 * database file (which is created if it doesn't exist), or
 * {@code jdbc:vsql::memory:} for a database that only exists for the life of
 * the connection:
 *
 * <pre>{@code
 * try (Connection conn = DriverManager.getConnection("jdbc:vsql:test.vsql")) {
 *     // ...
 * }
 * }</pre>
 *
 * <p>It's registered with {@link DriverManager} when it's loaded (as a
 * service, or by this class being initialized).
 */
public final class VsqlDriver implements Driver {
    /** The prefix of a vsql URL. */
    public static final String PREFIX = "jdbc:vsql:";

    static {
        try {
            DriverManager.registerDriver(new VsqlDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A driver, which doesn't need to be registered to connect. */
    public VsqlDriver() {
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /**
     * Connects to the database of the URL, or returns null if it isn't a vsql
     * URL. The properties aren't used.
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        String database = url.substring(PREFIX.length());
        if (database.isEmpty()) {
            throw new SQLException("vsql: the URL has no database (such as jdbc:vsql:test.vsql)", "08001");
        }

        return new VsqlConnection(url, database);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return Version.part(Version.DRIVER, 0);
    }

    @Override
    public int getMinorVersion() {
        return Version.part(Version.DRIVER, 1);
    }

    /** vsql isn't fully SQL-92 Entry Level compliant. */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw Errors.unsupported("logging");
    }
}

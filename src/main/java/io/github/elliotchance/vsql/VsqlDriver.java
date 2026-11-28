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
 * <p>The connection can be configured by properties, in the
 * {@link Properties} or after the path in the URL, each after a {@code ;},
 * such as {@code jdbc:vsql:test.vsql;readOnly=true;autoCommit=false}. So a
 * path that contains a {@code ;} can't be in a URL. A property in the URL
 * replaces the same property in the {@link Properties}. The properties are:
 *
 * <ul>
 * <li>{@code autoCommit}: {@code true} (the default) or {@code false}, see
 * {@link Connection#setAutoCommit(boolean)}.</li>
 * <li>{@code readOnly}: {@code true} or {@code false} (the default), see
 * {@link Connection#setReadOnly(boolean)}.</li>
 * <li>{@code transactionIsolation}: {@code READ_UNCOMMITTED},
 * {@code READ_COMMITTED} (the default), {@code REPEATABLE_READ} or
 * {@code SERIALIZABLE}, see {@link Connection#setTransactionIsolation(int)}.</li>
 * </ul>
 *
 * <p>An invalid value, or an unknown property in the URL, is an error
 * (SQLSTATE 08001). An unknown property in the {@link Properties} is ignored,
 * because they can have others, such as {@code user} and {@code password}
 * (vsql doesn't have users).
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
     * Connects to the database of the URL, with the connection properties of
     * the URL and info (which can be null), or returns null if it isn't a vsql
     * URL.
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        ConnectionProperties properties = new ConnectionProperties(url, info);
        if (properties.database.isEmpty()) {
            throw Errors.sql("08001", "vsql: the URL has no database (such as jdbc:vsql:test.vsql)");
        }

        VsqlConnection conn = new VsqlConnection(url, properties.database);
        try {
            if (properties.has(ConnectionProperties.AUTO_COMMIT)) {
                conn.setAutoCommit(properties.autoCommit());
            }
            if (properties.has(ConnectionProperties.READ_ONLY)) {
                conn.setReadOnly(properties.readOnly());
            }
            if (properties.has(ConnectionProperties.TRANSACTION_ISOLATION)) {
                conn.setTransactionIsolation(properties.transactionIsolation());
            }
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException closeError) {
                e.setNextException(closeError);
            }
            throw e;
        }
        return conn;
    }

    /**
     * Describes the connection properties (see {@link VsqlDriver}), with their
     * values in the URL or info (which can be null), or their defaults. It's
     * empty if it isn't a vsql URL.
     */
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return new DriverPropertyInfo[0];
        }
        return new ConnectionProperties(url, info).info();
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

package io.github.elliotchance.vsql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A DataSource for a vsql URL (see {@link VsqlDriver}), such as for a
 * connection pool. Each connection is a new connection to the database.
 *
 * <p>The connection properties (see {@link VsqlDriver}) can be set with
 * {@link #setAutoCommit(boolean)}, {@link #setReadOnly(boolean)} and
 * {@link #setTransactionIsolation(String)}, or in the URL. A property in the
 * URL replaces the one that's set here.
 */
public final class VsqlDataSource implements DataSource {
    private String url;
    private final Properties properties = new Properties();
    private int loginTimeout;
    private PrintWriter logWriter;

    /** A data source without a URL, see {@link #setUrl(String)}. */
    public VsqlDataSource() {
    }

    /** A data source for a URL, such as {@code jdbc:vsql:test.vsql}. */
    public VsqlDataSource(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /** The autoCommit connection property, true by default. */
    public boolean getAutoCommit() {
        return !"false".equals(properties.getProperty(ConnectionProperties.AUTO_COMMIT));
    }

    /** Sets the autoCommit connection property, see {@link VsqlDriver}. */
    public void setAutoCommit(boolean autoCommit) {
        properties.setProperty(ConnectionProperties.AUTO_COMMIT, Boolean.toString(autoCommit));
    }

    /** The readOnly connection property, false by default. */
    public boolean isReadOnly() {
        return "true".equals(properties.getProperty(ConnectionProperties.READ_ONLY));
    }

    /** Sets the readOnly connection property, see {@link VsqlDriver}. */
    public void setReadOnly(boolean readOnly) {
        properties.setProperty(ConnectionProperties.READ_ONLY, Boolean.toString(readOnly));
    }

    /**
     * The transactionIsolation connection property, or null if it isn't set
     * (which is READ_COMMITTED).
     */
    public String getTransactionIsolation() {
        return properties.getProperty(ConnectionProperties.TRANSACTION_ISOLATION);
    }

    /**
     * Sets the transactionIsolation connection property, such as
     * {@code SERIALIZABLE}, see {@link VsqlDriver}. An invalid level is an
     * error when connecting. Null unsets it.
     */
    public void setTransactionIsolation(String transactionIsolation) {
        if (transactionIsolation == null) {
            properties.remove(ConnectionProperties.TRANSACTION_ISOLATION);
        } else {
            properties.setProperty(ConnectionProperties.TRANSACTION_ISOLATION, transactionIsolation);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (url == null) {
            throw new SQLException("vsql: the data source has no URL", "08001");
        }
        Connection conn = new VsqlDriver().connect(url, properties);
        if (conn == null) {
            throw new SQLException("vsql: " + url + " isn't a vsql URL (jdbc:vsql:...)", "08001");
        }
        return conn;
    }

    /** vsql doesn't have users, so they're ignored. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw Errors.unsupported("logging");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("vsql: not a wrapper for " + iface.getName(), "HY000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}

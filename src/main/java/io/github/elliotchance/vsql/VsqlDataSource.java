package io.github.elliotchance.vsql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A DataSource for a vsql URL (see {@link VsqlDriver}), such as for a
 * connection pool. Each connection is a new connection to the database.
 */
public final class VsqlDataSource implements DataSource {
    private String url;
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

    @Override
    public Connection getConnection() throws SQLException {
        if (url == null) {
            throw new SQLException("vsql: the data source has no URL", "08001");
        }
        Connection conn = new VsqlDriver().connect(url, null);
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

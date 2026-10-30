package io.github.elliotchance.vsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A connection to a vsql database, see {@link VsqlDriver}.
 *
 * <p>Each connection has its own libvsql environment, so connections can be
 * used from different threads at the same time. A connection (and its
 * statements and result sets) can also be shared by threads, which use it one
 * at a time.
 *
 * <p>Auto-commit is on by default: each statement is committed when it
 * finishes (or when its result set is closed). With auto-commit off, the first
 * statement starts a transaction, which is ended by {@link #commit()} or
 * {@link #rollback()}. vsql's transactions are READ COMMITTED.
 */
public final class VsqlConnection extends UnsupportedConnection {
    private final String url;
    private final int env;
    private int dbc;
    private boolean closed;
    private boolean autoCommit = true;
    private boolean inTransaction;
    private boolean readOnly;
    private int isolation = TRANSACTION_READ_COMMITTED;
    private final Set<VsqlStatement> statements = new LinkedHashSet<>();

    VsqlConnection(String url, String database) throws SQLException {
        this.url = url;
        env = Native.allocEnv();
        try {
            dbc = Native.allocHandle(Native.SQL_HANDLE_DBC, Native.SQL_HANDLE_ENV, env);
        } catch (SQLException e) {
            Native.freeHandle(Native.SQL_HANDLE_ENV, env);
            throw e;
        }
        try {
            Native.connect(dbc, database);
        } catch (SQLException e) {
            Native.freeHandle(Native.SQL_HANDLE_DBC, dbc);
            Native.freeHandle(Native.SQL_HANDLE_ENV, env);
            throw e;
        }

        // Each statement is committed when it finishes, unless it's in a
        // transaction started by begin.
        try {
            Native.setAutocommit(dbc, true);
        } catch (SQLException e) {
            close();
            throw e;
        }
    }

    int dbc() {
        return dbc;
    }

    String url() {
        return url;
    }

    void check() throws SQLException {
        if (closed) {
            throw Errors.closed("connection");
        }
    }

    /**
     * Starts a transaction before a statement is executed, unless auto-commit
     * is on or there already is one.
     */
    synchronized void begin() throws SQLException {
        if (autoCommit || inTransaction) {
            return;
        }
        int level = isolation == TRANSACTION_READ_UNCOMMITTED
                ? Native.SQL_TRANSACTION_READ_UNCOMMITTED
                : Native.SQL_TRANSACTION_READ_COMMITTED;
        Native.startTran(dbc, Native.SQL_TRANSACTION_READ_WRITE, level);
        inTransaction = true;
    }

    synchronized void remove(VsqlStatement statement) {
        statements.remove(statement);
    }

    private synchronized <T extends VsqlStatement> T add(T statement) {
        statements.add(statement);
        return statement;
    }

    private synchronized void end(boolean commit) throws SQLException {
        check();
        if (autoCommit) {
            throw new SQLException("vsql: there's no transaction with auto-commit on", "25000");
        }
        if (!inTransaction) {
            return;
        }

        // The result sets are closed by the end of the transaction.
        for (VsqlStatement statement : new ArrayList<>(statements)) {
            statement.closeResultSet();
        }
        inTransaction = false;
        Native.endTran(dbc, commit);
    }

    @Override
    public synchronized void commit() throws SQLException {
        end(true);
    }

    @Override
    public synchronized void rollback() throws SQLException {
        end(false);
    }

    /** Turning auto-commit on commits the transaction. */
    @Override
    public synchronized void setAutoCommit(boolean autoCommit) throws SQLException {
        check();
        if (autoCommit == this.autoCommit) {
            return;
        }
        if (autoCommit && inTransaction) {
            end(true);
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public synchronized boolean getAutoCommit() throws SQLException {
        check();
        return autoCommit;
    }

    /**
     * vsql's transactions are READ COMMITTED, so only READ UNCOMMITTED (which
     * is READ COMMITTED) and READ COMMITTED are supported.
     */
    @Override
    public synchronized void setTransactionIsolation(int level) throws SQLException {
        check();
        switch (level) {
            case TRANSACTION_READ_UNCOMMITTED, TRANSACTION_READ_COMMITTED -> isolation = level;
            case TRANSACTION_REPEATABLE_READ, TRANSACTION_SERIALIZABLE ->
                    throw new java.sql.SQLFeatureNotSupportedException(
                            "vsql: transactions are READ COMMITTED", "HYC00");
            default -> throw new SQLException("vsql: invalid isolation level " + level, "HY024");
        }
    }

    @Override
    public synchronized int getTransactionIsolation() throws SQLException {
        check();
        return isolation;
    }

    /** Read only is a hint, which vsql doesn't use. */
    @Override
    public synchronized void setReadOnly(boolean readOnly) throws SQLException {
        check();
        this.readOnly = readOnly;
    }

    @Override
    public synchronized boolean isReadOnly() throws SQLException {
        check();
        return readOnly;
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return createStatement(resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public synchronized Statement createStatement(int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        check();
        checkResultSet(resultSetType, resultSetConcurrency, resultSetHoldability);
        return add(new VsqlStatement(this));
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return prepareStatement(sql, resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public synchronized PreparedStatement prepareStatement(String sql, int resultSetType,
            int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        check();
        checkResultSet(resultSetType, resultSetConcurrency, resultSetHoldability);
        return add(new VsqlPreparedStatement(this, sql));
    }

    /** Only NO_GENERATED_KEYS is supported. */
    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw Errors.unsupported("generated keys");
        }
        return prepareStatement(sql);
    }

    /** Result sets are forward only and read only, and close at commit. */
    private static void checkResultSet(int type, int concurrency, int holdability) throws SQLException {
        if (type != ResultSet.TYPE_FORWARD_ONLY) {
            throw Errors.unsupported("a scrollable result set");
        }
        if (concurrency != ResultSet.CONCUR_READ_ONLY) {
            throw Errors.unsupported("an updatable result set");
        }
        if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw Errors.unsupported("a holdable result set");
        }
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        check();
        return sql;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        check();
        return new VsqlDatabaseMetaData(this);
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("vsql: the timeout can't be negative", "HY092");
        }
        return !closed;
    }

    /**
     * Closes the connection and its statements. A transaction that hasn't been
     * committed is rolled back. It does nothing if it's already closed.
     */
    @Override
    public synchronized void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;

        SQLException error = null;
        for (VsqlStatement statement : new ArrayList<>(statements)) {
            try {
                statement.close();
            } catch (SQLException e) {
                error = add(error, e);
            }
        }
        if (inTransaction) {
            inTransaction = false;
            try {
                Native.endTran(dbc, false);
            } catch (SQLException e) {
                error = add(error, e);
            }
        }
        for (Native.Call call : new Native.Call[] {
                () -> Native.disconnect(dbc),
                () -> Native.freeHandle(Native.SQL_HANDLE_DBC, dbc),
                () -> Native.freeHandle(Native.SQL_HANDLE_ENV, env),
        }) {
            try {
                call.run();
            } catch (SQLException e) {
                error = add(error, e);
            }
        }
        if (error != null) {
            throw error;
        }
    }

    private static SQLException add(SQLException error, SQLException e) {
        if (error == null) {
            return e;
        }
        error.setNextException(e);
        return error;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("vsql: the executor can't be null", "HY009");
        }
        close();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        check();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        check();
    }

    /** vsql doesn't have catalogs, so it's ignored. */
    @Override
    public void setCatalog(String catalog) throws SQLException {
        check();
    }

    @Override
    public String getCatalog() throws SQLException {
        check();
        return null;
    }

    @Override
    public String getSchema() throws SQLException {
        check();
        return null;
    }

    @Override
    public int getHoldability() throws SQLException {
        check();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        check();
        if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw Errors.unsupported("a holdable result set");
        }
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        check();
        return new HashMap<>();
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        check();
        return 0;
    }

    /** Client info isn't used, so it's ignored. */
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        check();
        return null;
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        check();
        return new Properties();
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

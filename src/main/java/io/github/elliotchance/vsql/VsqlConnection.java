package io.github.elliotchance.vsql;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
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
 * {@link #rollback()}, and can have savepoints. Each transaction has the
 * isolation level of {@link #setTransactionIsolation(int)} (READ COMMITTED by
 * default), and is READ ONLY if {@link #setReadOnly(boolean)} is true. The
 * statements that are executed with auto-commit on are also READ ONLY then,
 * but they have vsql's default isolation level (SERIALIZABLE), since a READ
 * COMMITTED statement that changes data fails straight away (SQLSTATE 40001)
 * when another connection is changing data, rather than waiting for it.
 *
 * <p>vsql's own default is SERIALIZABLE, but a SERIALIZABLE transaction that
 * changes a table makes the other connections that use the table wait for it
 * to finish (for up to 5 seconds, then it's SQLSTATE 40001), so the driver's
 * default is READ COMMITTED.
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
    private int savepoints;
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
        // A READ UNCOMMITTED transaction must be READ ONLY. The JDBC isolation
        // levels are the same as the SQL/CLI ones.
        int access = readOnly || isolation == TRANSACTION_READ_UNCOMMITTED
                ? Native.SQL_TRANSACTION_READ_ONLY
                : Native.SQL_TRANSACTION_READ_WRITE;
        Native.startTran(dbc, access, isolation);
        inTransaction = true;
    }

    /**
     * Forgets the transaction if it has ended without the driver ending it,
     * which is called after each statement (whether it succeeded or not).
     * That's a COMMIT or ROLLBACK executed as SQL, or an error that rolled
     * back the transaction (such as a 40001 serialization failure). Otherwise
     * the next statement wouldn't start a transaction, and libvsql would
     * commit it by itself.
     */
    synchronized void statementEnded() {
        if (!inTransaction || closed) {
            return;
        }
        try {
            if (!Native.transactionActive(dbc)) {
                inTransaction = false;
            }
        } catch (SQLException e) {
            // The transaction is assumed to still be active, as it was.
        }
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
            throw Errors.sql("25000", "vsql: there's no transaction with auto-commit on");
        }
        if (!inTransaction) {
            return;
        }

        // The result sets are closed by the end of the transaction.
        closeResultSets();
        inTransaction = false;
        Native.endTran(dbc, commit);
    }

    private void closeResultSets() throws SQLException {
        for (VsqlStatement statement : new ArrayList<>(statements)) {
            statement.closeResultSet();
        }
    }

    /**
     * Makes a savepoint in the transaction (which is started if there isn't
     * one). Auto-commit must be off.
     */
    @Override
    public synchronized Savepoint setSavepoint() throws SQLException {
        return newSavepoint(null);
    }

    /** A named savepoint (any name can be given), see {@link #setSavepoint()}. */
    @Override
    public synchronized Savepoint setSavepoint(String name) throws SQLException {
        if (name == null) {
            throw Errors.sql("HY009", "vsql: the savepoint name can't be null");
        }
        return newSavepoint(name);
    }

    private Savepoint newSavepoint(String name) throws SQLException {
        check();
        if (autoCommit) {
            throw Errors.sql("25000", "vsql: there are no savepoints with auto-commit on");
        }
        begin();
        VsqlSavepoint savepoint = new VsqlSavepoint(this, ++savepoints, name);
        execute("SAVEPOINT " + savepoint.sqlName);
        return savepoint;
    }

    /** Executes a statement that doesn't have a result, on its own handle. */
    private void execute(String sql) throws SQLException {
        int stmt = Native.allocHandle(Native.SQL_HANDLE_STMT, Native.SQL_HANDLE_DBC, dbc);
        try {
            Native.execDirect(stmt, sql);
        } finally {
            Native.freeHandle(Native.SQL_HANDLE_STMT, stmt);
        }
    }

    /**
     * The value of a query that has one character string column and one row,
     * executed on its own handle.
     */
    private String queryString(String sql) throws SQLException {
        int stmt = Native.allocHandle(Native.SQL_HANDLE_STMT, Native.SQL_HANDLE_DBC, dbc);
        try {
            Native.execDirect(stmt, sql);
            Column[] columns = {Native.describeCol(stmt, 1)};
            Object[] row = new Native.Fetcher(stmt, columns).next();
            Native.closeCursor(stmt);
            return row == null || row[0] == null ? null : row[0].toString();
        } finally {
            Native.freeHandle(Native.SQL_HANDLE_STMT, stmt);
        }
    }

    /** A character string literal of s. */
    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private VsqlSavepoint savepoint(Savepoint savepoint) throws SQLException {
        check();
        if (autoCommit) {
            throw Errors.sql("25000", "vsql: there are no savepoints with auto-commit on");
        }
        if (!(savepoint instanceof VsqlSavepoint s) || s.connection != this) {
            throw Errors.sql("3B001", "vsql: the savepoint isn't from this connection");
        }
        return s;
    }

    /**
     * Rolls back the changes after the savepoint, which also destroys the
     * savepoints after it. It closes the result sets.
     */
    @Override
    public synchronized void rollback(Savepoint savepoint) throws SQLException {
        VsqlSavepoint s = savepoint(savepoint);
        closeResultSets();
        Native.endSavepoint(dbc, s.sqlName, Native.SQL_SAVEPOINT_NAME_ROLLBACK);
    }

    /** Destroys the savepoint (and the savepoints after it). */
    @Override
    public synchronized void releaseSavepoint(Savepoint savepoint) throws SQLException {
        VsqlSavepoint s = savepoint(savepoint);
        Native.endSavepoint(dbc, s.sqlName, Native.SQL_SAVEPOINT_NAME_RELEASE);
    }

    /** A BLOB in memory, which can be a parameter. */
    @Override
    public Blob createBlob() throws SQLException {
        check();
        return new VsqlBlob(new byte[0]);
    }

    /** A CLOB in memory, which can be a parameter. */
    @Override
    public Clob createClob() throws SQLException {
        check();
        return new VsqlClob("");
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
     * The isolation level of the transactions that are started after it. vsql
     * runs READ UNCOMMITTED as READ COMMITTED and REPEATABLE READ as
     * SERIALIZABLE. A READ UNCOMMITTED transaction is READ ONLY, as the SQL
     * standard requires. It can't be changed during a transaction.
     */
    @Override
    public synchronized void setTransactionIsolation(int level) throws SQLException {
        check();
        switch (level) {
            case TRANSACTION_READ_UNCOMMITTED, TRANSACTION_READ_COMMITTED, TRANSACTION_REPEATABLE_READ,
                    TRANSACTION_SERIALIZABLE -> {
                notInTransaction("isolation level");
                isolation = level;
            }
            default -> throw Errors.sql("HY024", "vsql: invalid isolation level " + level);
        }
    }

    private void notInTransaction(String what) throws SQLException {
        if (inTransaction) {
            throw Errors.sql("25001", "vsql: the " + what + " can't be changed during a transaction");
        }
    }

    @Override
    public synchronized int getTransactionIsolation() throws SQLException {
        check();
        return isolation;
    }

    /**
     * Makes the transactions that are started after it READ ONLY, including the
     * one of each statement with auto-commit on, so that changing data is an
     * error (SQLSTATE 25006). It can't be changed during a transaction.
     */
    @Override
    public synchronized void setReadOnly(boolean readOnly) throws SQLException {
        check();
        notInTransaction("access mode");

        // The session's access mode is the one of each statement with
        // auto-commit on (its isolation level stays vsql's default).
        int stmt = Native.allocHandle(Native.SQL_HANDLE_STMT, Native.SQL_HANDLE_DBC, dbc);
        try {
            Native.execDirect(stmt, "SET SESSION CHARACTERISTICS AS TRANSACTION "
                    + (readOnly ? "READ ONLY" : "READ WRITE"));
        } finally {
            Native.freeHandle(Native.SQL_HANDLE_STMT, stmt);
        }
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
            throw Errors.sql("HY092", "vsql: the timeout can't be negative");
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
        // A transaction can also have been started by a START TRANSACTION
        // executed as SQL (even with auto-commit on).
        boolean active = inTransaction;
        if (!active) {
            try {
                active = Native.transactionActive(dbc);
            } catch (SQLException e) {
                error = add(error, e);
            }
        }
        if (active) {
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
            throw Errors.sql("HY009", "vsql: the executor can't be null");
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

    /**
     * Changes the current catalog (with SET CATALOG), which is the database
     * that unqualified schema names are in. A catalog is a database, named by
     * its file name without the extension (such as "test" for test.vsql, or
     * ":memory:"). The name is the name of the catalog exactly (as
     * {@link #getCatalog()} returns it), otherwise it's an identifier, so a
     * catalog named "FOO" is also found by "foo". A catalog that doesn't exist
     * is an error (SQLSTATE 3D000), and the current catalog doesn't change. It
     * can be changed during a transaction, and doesn't start one.
     */
    @Override
    public synchronized void setCatalog(String catalog) throws SQLException {
        check();
        if (catalog == null) {
            throw Errors.sql("HY009", "vsql: the catalog can't be null");
        }
        execute("SET CATALOG " + literal(catalog));
    }

    /** The current catalog (CURRENT_CATALOG), such as ":memory:". */
    @Override
    public synchronized String getCatalog() throws SQLException {
        check();
        return queryString("VALUES CURRENT_CATALOG");
    }

    /**
     * Changes the current schema (with SET SCHEMA), which is the schema of
     * unqualified table names. The name is the name of the schema exactly (as
     * {@link #getSchema()} returns it, such as "PUBLIC"), it isn't an
     * identifier, so CREATE SCHEMA foo is "FOO", and "foo" is a different
     * schema. A schema that doesn't exist is an error (SQLSTATE 3F000), and
     * the current schema doesn't change. It can be changed during a
     * transaction, and doesn't start one.
     */
    @Override
    public synchronized void setSchema(String schema) throws SQLException {
        check();
        if (schema == null) {
            throw Errors.sql("HY009", "vsql: the schema can't be null");
        }
        // The value of SET SCHEMA is a schema name, so it's delimited to be
        // exactly the name.
        execute("SET SCHEMA " + literal("\"" + schema.replace("\"", "\"\"") + "\""));
    }

    /** The current schema (CURRENT_SCHEMA), which is "PUBLIC" by default. */
    @Override
    public synchronized String getSchema() throws SQLException {
        check();
        return queryString("VALUES CURRENT_SCHEMA");
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
        throw Errors.sql("HY000", "vsql: not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}

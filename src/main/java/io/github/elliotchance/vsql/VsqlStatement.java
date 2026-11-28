package io.github.elliotchance.vsql;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A statement, see {@link Connection#createStatement()}. It has its own libvsql
 * statement handle (allocated when it's first executed), so it has at most one
 * open result set, which is {@link ResultSet#TYPE_FORWARD_ONLY} or
 * {@link ResultSet#TYPE_SCROLL_INSENSITIVE}.
 */
public class VsqlStatement extends UnsupportedStatement {
    final VsqlConnection connection;
    /**
     * Set by the constructor (or, for a prepared statement, straight after it,
     * since UnsupportedPreparedStatement only passes the connection).
     */
    int resultSetType;
    int stmt;
    private boolean closed;
    private VsqlResultSet resultSet;
    private long updateCount = -1;
    private long maxRows;
    private int fetchSize;
    private int fetchDirection = ResultSet.FETCH_FORWARD;
    private int queryTimeout;
    private int maxFieldSize;
    private boolean poolable;
    private boolean closeOnCompletion;
    private final List<String> batch = new ArrayList<>();

    VsqlStatement(VsqlConnection connection) {
        this(connection, ResultSet.TYPE_FORWARD_ONLY);
    }

    VsqlStatement(VsqlConnection connection, int resultSetType) {
        this.connection = connection;
        this.resultSetType = resultSetType;
    }

    void check() throws SQLException {
        if (closed) {
            throw Errors.closed("statement");
        }
        connection.check();
    }

    /**
     * The statement handle, which is allocated when it's first needed (with a
     * scrollable, insensitive cursor for TYPE_SCROLL_INSENSITIVE).
     */
    int handle() throws SQLException {
        if (stmt == 0) {
            stmt = Native.allocHandle(Native.SQL_HANDLE_STMT, Native.SQL_HANDLE_DBC, connection.dbc());
            if (resultSetType == ResultSet.TYPE_SCROLL_INSENSITIVE) {
                Native.setStmtAttr(stmt, Native.SQL_ATTR_CURSOR_SCROLLABLE, Native.SQL_SCROLLABLE);
                Native.setStmtAttr(stmt, Native.SQL_ATTR_CURSOR_SENSITIVITY, Native.SQL_INSENSITIVE);
            }
        }
        return stmt;
    }

    /**
     * Closes the result set, if it's open. This completes the statement, so
     * it's closed too with closeOnCompletion (such as at the end of a
     * transaction).
     */
    void closeResultSet() throws SQLException {
        closeResultSet(true);
    }

    /**
     * Closes the result set without completing the statement, because the
     * statement is executed again (or moves to its next result). JDBC says
     * that this doesn't close a statement with closeOnCompletion.
     */
    void discardResultSet() throws SQLException {
        closeResultSet(false);
    }

    private void closeResultSet(boolean completes) throws SQLException {
        synchronized (connection) {
            if (resultSet != null) {
                VsqlResultSet rs = resultSet;
                resultSet = null;
                try {
                    rs.close();
                } finally {
                    if (completes && closeOnCompletion) {
                        close();
                    }
                }
            }
        }
    }

    /** Called by the result set when it's closed (by the user). */
    void resultSetClosed(VsqlResultSet rs) throws SQLException {
        synchronized (connection) {
            if (resultSet == rs) {
                resultSet = null;
                if (closeOnCompletion) {
                    close();
                }
            }
        }
    }

    /** Prepares to execute: closes the result set and starts a transaction. */
    void beforeExecute() throws SQLException {
        check();
        discardResultSet();
        updateCount = -1;
        connection.begin();
    }

    /** The result of the statement that was executed, it's true for a query. */
    boolean afterExecute() throws SQLException {
        int columns = Native.numResultCols(stmt);
        if (columns == 0) {
            updateCount = Native.rowCount(stmt);
            return false;
        }

        Column[] cs = new Column[columns];
        for (int i = 0; i < columns; i++) {
            cs[i] = Native.describeCol(stmt, i + 1);
        }
        resultSet = new VsqlResultSet(this, cs, resultSetType, maxRows, fetchSize, fetchDirection);
        return true;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        synchronized (connection) {
            beforeExecute();
            try {
                Native.execDirect(handle(), sql);
            } finally {
                connection.statementEnded();
            }
            return afterExecute();
        }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        synchronized (connection) {
            if (!execute(sql)) {
                throw Errors.sql("07000", "vsql: the statement isn't a query");
            }
            return resultSet;
        }
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return (int) executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        synchronized (connection) {
            if (execute(sql)) {
                discardResultSet();
                throw Errors.sql("07000", "vsql: the statement is a query");
            }
            return updateCount;
        }
    }

    /** Only NO_GENERATED_KEYS is supported. */
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        noGeneratedKeys(autoGeneratedKeys);
        return execute(sql);
    }

    /** Only NO_GENERATED_KEYS is supported. */
    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        noGeneratedKeys(autoGeneratedKeys);
        return executeUpdate(sql);
    }

    static void noGeneratedKeys(int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw Errors.unsupported("generated keys");
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        synchronized (connection) {
            check();
            return resultSet;
        }
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return (int) getLargeUpdateCount();
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        synchronized (connection) {
            check();
            return updateCount;
        }
    }

    /** Each statement has one result, so there are no more. */
    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(CLOSE_CURRENT_RESULT);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        synchronized (connection) {
            check();
            discardResultSet();
            updateCount = -1;
            return false;
        }
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        synchronized (connection) {
            check();
            batch.add(sql);
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        synchronized (connection) {
            check();
            batch.clear();
        }
    }

    /** Executes each statement of the batch, which can't be a query. */
    @Override
    public int[] executeBatch() throws SQLException {
        synchronized (connection) {
            check();
            List<String> statements = new ArrayList<>(batch);
            batch.clear();
            long[] counts = new long[statements.size()];
            for (int i = 0; i < statements.size(); i++) {
                try {
                    counts[i] = executeLargeUpdate(statements.get(i));
                } catch (SQLException e) {
                    throw batchError(e, counts, i);
                }
            }
            return toInts(counts);
        }
    }

    static BatchUpdateException batchError(SQLException e, long[] counts, int done) {
        long[] completed = java.util.Arrays.copyOf(counts, done);
        return new BatchUpdateException(e.getMessage(), e.getSQLState(), e.getErrorCode(), completed, e);
    }

    static int[] toInts(long[] counts) {
        int[] ints = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            ints[i] = (int) counts[i];
        }
        return ints;
    }

    @Override
    public Connection getConnection() throws SQLException {
        check();
        return connection;
    }

    @Override
    public boolean isClosed() {
        synchronized (connection) {
            return closed;
        }
    }

    /** Closes the statement and its result set. It does nothing if it's closed. */
    @Override
    public void close() throws SQLException {
        synchronized (connection) {
            if (closed) {
                return;
            }
            closed = true;
            connection.remove(this);
            if (connection.isClosed() && stmt == 0) {
                return;
            }
            try {
                closeResultSet();
            } finally {
                if (stmt != 0) {
                    int handle = stmt;
                    stmt = 0;
                    Native.freeHandle(Native.SQL_HANDLE_STMT, handle);
                }
            }
        }
    }

    /**
     * The statement is closed when its result set is closed by the user, or
     * by the end of the transaction. Executing the statement again (or
     * {@link #getMoreResults()}) closes the result set, but not the statement.
     */
    @Override
    public void closeOnCompletion() throws SQLException {
        check();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        check();
        return closeOnCompletion;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        setLargeMaxRows(max);
    }

    @Override
    public int getMaxRows() throws SQLException {
        return (int) Math.min(getLargeMaxRows(), Integer.MAX_VALUE);
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        check();
        if (max < 0) {
            throw Errors.sql("HY024", "vsql: max rows can't be negative");
        }
        maxRows = max;
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        check();
        return maxRows;
    }

    /** The fetch size is a hint, which vsql doesn't use. */
    @Override
    public void setFetchSize(int rows) throws SQLException {
        check();
        if (rows < 0) {
            throw Errors.sql("HY024", "vsql: the fetch size can't be negative");
        }
        fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        check();
        return fetchSize;
    }

    /**
     * The fetch direction is a hint, which vsql doesn't use. It can only be
     * FETCH_FORWARD for TYPE_FORWARD_ONLY.
     */
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        check();
        checkFetchDirection(resultSetType, direction);
        fetchDirection = direction;
    }

    static void checkFetchDirection(int type, int direction) throws SQLException {
        if (direction != ResultSet.FETCH_FORWARD && direction != ResultSet.FETCH_REVERSE
                && direction != ResultSet.FETCH_UNKNOWN) {
            throw new SQLException("vsql: invalid fetch direction " + direction, "HY024");
        }
        if (type == ResultSet.TYPE_FORWARD_ONLY && direction != ResultSet.FETCH_FORWARD) {
            throw Errors.unsupported("a fetch direction other than FETCH_FORWARD");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        check();
        return fetchDirection;
    }

    /**
     * The query timeout is kept, but vsql can't cancel a statement, so it
     * isn't used.
     */
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        check();
        if (seconds < 0) {
            throw Errors.sql("HY024", "vsql: the query timeout can't be negative");
        }
        queryTimeout = seconds;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        check();
        return queryTimeout;
    }

    /** The max field size is kept, but it isn't used. */
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        check();
        if (max < 0) {
            throw Errors.sql("HY024", "vsql: the max field size can't be negative");
        }
        maxFieldSize = max;
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        check();
        return maxFieldSize;
    }

    /**
     * Names the cursor of the statement's next result sets, so that a
     * positioned UPDATE or DELETE ({@code WHERE CURRENT OF name}) on another
     * statement of the connection can change its current row. The name is an
     * identifier (so c is the same cursor as C, but not as "c"), it can't
     * start with SQL_CUR, and two statements of a connection can't have the
     * same name (SQLSTATE 34000). It can't be changed while the statement has
     * an open result set (SQLSTATE 24000).
     */
    @Override
    public void setCursorName(String name) throws SQLException {
        synchronized (connection) {
            check();
            Native.setCursorName(handle(), name);
        }
    }

    /** There are no escapes to process, so it's ignored. */
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        check();
    }

    @Override
    public int getResultSetType() throws SQLException {
        check();
        return resultSetType;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        check();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        check();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        check();
        this.poolable = poolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        check();
        return poolable;
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

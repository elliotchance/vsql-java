package io.github.elliotchance.vsql;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * A prepared statement, see {@link java.sql.Connection#prepareStatement(String)}.
 * Parameters are {@code ?}, and each one's Java type is sent as an SQL type,
 * which is cast to the type it's used as (see the package documentation).
 */
public final class VsqlPreparedStatement extends UnsupportedPreparedStatement {
    private Param[] params = new Param[0];
    private final List<Param[]> batch = new ArrayList<>();
    // The dynamic parameters, which are read when the statement is prepared,
    // since binding a parameter replaces its record in the IPD.
    private final Column[] parameters;

    VsqlPreparedStatement(VsqlConnection connection, String sql) throws SQLException {
        super(connection);
        int handle = handle();
        try {
            Native.prepare(handle, sql);
            parameters = Native.describeParams(handle);
        } catch (SQLException e) {
            close();
            throw e;
        }
    }

    private void set(int index, Param param) throws SQLException {
        synchronized (connection) {
            check();
            if (index < 1) {
                throw new SQLException("vsql: invalid parameter index " + index, "07009");
            }
            if (index > params.length) {
                params = Arrays.copyOf(params, index);
            }
            params[index - 1] = param;
        }
    }

    private void set(int index, Object x) throws SQLException {
        set(index, Values.param(x));
    }

    /** The parameters, which must all be set. */
    private Param[] params() throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) {
                throw new SQLException("vsql: parameter " + (i + 1) + " isn't set", "07002");
            }
        }
        return params.clone();
    }

    private boolean run(Param[] values) throws SQLException {
        beforeExecute();
        Native.execute(stmt, values);
        return afterExecute();
    }

    @Override
    public boolean execute() throws SQLException {
        synchronized (connection) {
            check();
            return run(params());
        }
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        synchronized (connection) {
            if (!execute()) {
                throw new SQLException("vsql: the statement isn't a query", "07000");
            }
            return getResultSet();
        }
    }

    @Override
    public int executeUpdate() throws SQLException {
        return (int) executeLargeUpdate();
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        synchronized (connection) {
            if (execute()) {
                closeResultSet();
                throw new SQLException("vsql: the statement is a query", "07000");
            }
            return getLargeUpdateCount();
        }
    }

    @Override
    public void clearParameters() throws SQLException {
        synchronized (connection) {
            check();
            params = new Param[0];
        }
    }

    @Override
    public void addBatch() throws SQLException {
        synchronized (connection) {
            check();
            batch.add(params());
        }
    }

    @Override
    public void clearBatch() throws SQLException {
        synchronized (connection) {
            check();
            batch.clear();
        }
    }

    /** Executes the statement with each set of parameters of the batch. */
    @Override
    public int[] executeBatch() throws SQLException {
        synchronized (connection) {
            check();
            List<Param[]> sets = new ArrayList<>(batch);
            batch.clear();
            long[] counts = new long[sets.size()];
            for (int i = 0; i < sets.size(); i++) {
                try {
                    if (run(sets.get(i))) {
                        closeResultSet();
                        throw new SQLException("vsql: the statement is a query", "07000");
                    }
                    counts[i] = getLargeUpdateCount();
                } catch (SQLException e) {
                    throw batchError(e, counts, i);
                }
            }
            return toInts(counts);
        }
    }

    // The Statement methods with SQL can't be used with a PreparedStatement.

    private static SQLException withSql() {
        return new SQLException("vsql: a PreparedStatement can't execute other SQL", "HY000");
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw withSql();
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw withSql();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw withSql();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw withSql();
    }

    /** The columns of the query, or null if it isn't a query. */
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        synchronized (connection) {
            check();
            int n = Native.numResultCols(stmt);
            if (n == 0) {
                return null;
            }
            Column[] columns = new Column[n];
            for (int i = 0; i < n; i++) {
                columns[i] = Native.describeCol(stmt, i + 1);
            }
            return new VsqlResultSetMetaData(columns);
        }
    }

    /**
     * The type of each parameter, which vsql determines from where it's used
     * (such as the type of x in "x = ?") when the statement is prepared.
     */
    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        synchronized (connection) {
            check();
            return new VsqlParameterMetaData(parameters);
        }
    }

    @Override
    public void setNull(int index, int sqlType) throws SQLException {
        set(index, (Object) null);
    }

    @Override
    public void setNull(int index, int sqlType, String typeName) throws SQLException {
        set(index, (Object) null);
    }

    @Override
    public void setBoolean(int index, boolean x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setByte(int index, byte x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setShort(int index, short x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setInt(int index, int x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setLong(int index, long x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setFloat(int index, float x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setDouble(int index, double x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setBigDecimal(int index, BigDecimal x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setString(int index, String x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setNString(int index, String x) throws SQLException {
        set(index, (Object) x);
    }

    /** A BINARY VARYING. */
    @Override
    public void setBytes(int index, byte[] x) throws SQLException {
        set(index, (Object) x);
    }

    /** A BINARY LARGE OBJECT. */
    @Override
    public void setBlob(int index, Blob x) throws SQLException {
        set(index, (Object) x);
    }

    /** A BINARY LARGE OBJECT, read from the stream now. */
    @Override
    public void setBlob(int index, InputStream x) throws SQLException {
        setBlob(index, x, -1);
    }

    /** A BINARY LARGE OBJECT of length octets, read from the stream now. */
    @Override
    public void setBlob(int index, InputStream x, long length) throws SQLException {
        set(index, x == null ? null : new VsqlBlob(read(x, length)));
    }

    /** A BINARY VARYING, read from the stream now. */
    @Override
    public void setBinaryStream(int index, InputStream x) throws SQLException {
        setBinaryStream(index, x, -1L);
    }

    /** A BINARY VARYING of length octets, read from the stream now. */
    @Override
    public void setBinaryStream(int index, InputStream x, int length) throws SQLException {
        setBinaryStream(index, x, (long) length);
    }

    /** A BINARY VARYING of length octets, read from the stream now. */
    @Override
    public void setBinaryStream(int index, InputStream x, long length) throws SQLException {
        set(index, x == null ? null : (Object) read(x, length));
    }

    /** A CHARACTER LARGE OBJECT. */
    @Override
    public void setClob(int index, Clob x) throws SQLException {
        set(index, (Object) x);
    }

    /** A CHARACTER LARGE OBJECT, read from the reader now. */
    @Override
    public void setClob(int index, Reader x) throws SQLException {
        setClob(index, x, -1);
    }

    /** A CHARACTER LARGE OBJECT of length chars, read from the reader now. */
    @Override
    public void setClob(int index, Reader x, long length) throws SQLException {
        set(index, x == null ? Values.param(null) : Values.clob(read(x, length)));
    }

    /** A CHARACTER LARGE OBJECT, read from the reader now. */
    @Override
    public void setCharacterStream(int index, Reader x) throws SQLException {
        setClob(index, x, -1);
    }

    /** A CHARACTER LARGE OBJECT of length chars, read from the reader now. */
    @Override
    public void setCharacterStream(int index, Reader x, int length) throws SQLException {
        setClob(index, x, length);
    }

    /** A CHARACTER LARGE OBJECT of length chars, read from the reader now. */
    @Override
    public void setCharacterStream(int index, Reader x, long length) throws SQLException {
        setClob(index, x, length);
    }

    /** All of a stream (for a negative length), or length octets of it. */
    private static byte[] read(InputStream in, long length) throws SQLException {
        try {
            if (length < 0) {
                return in.readAllBytes();
            }
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("vsql: the stream is too long", "22001");
            }
            byte[] b = in.readNBytes((int) length);
            if (b.length < length) {
                throw new SQLException("vsql: the stream has fewer than " + length + " octets", "22023");
            }
            return b;
        } catch (IOException e) {
            throw new SQLException("vsql: can't read the stream: " + e.getMessage(), "HY000", e);
        }
    }

    /** All of a reader (for a negative length), or length chars of it. */
    private static String read(Reader in, long length) throws SQLException {
        try {
            if (length < 0) {
                StringWriter w = new StringWriter();
                in.transferTo(w);
                return w.toString();
            }
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("vsql: the reader is too long", "22001");
            }
            char[] buf = new char[(int) length];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) {
                    throw new SQLException("vsql: the reader has fewer than " + length + " chars", "22023");
                }
                n += r;
            }
            return new String(buf);
        } catch (IOException e) {
            throw new SQLException("vsql: can't read the reader: " + e.getMessage(), "HY000", e);
        }
    }

    @Override
    public void setDate(int index, Date x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setTime(int index, Time x) throws SQLException {
        set(index, (Object) x);
    }

    @Override
    public void setTimestamp(int index, Timestamp x) throws SQLException {
        set(index, (Object) x);
    }

    /** The date in the calendar's time zone. */
    @Override
    public void setDate(int index, Date x, Calendar cal) throws SQLException {
        set(index, x == null || cal == null ? x : (Object) local(x, cal).toLocalDate());
    }

    /** The time in the calendar's time zone. */
    @Override
    public void setTime(int index, Time x, Calendar cal) throws SQLException {
        set(index, x == null || cal == null ? x : (Object) local(x, cal).toLocalTime());
    }

    /** The timestamp in the calendar's time zone. */
    @Override
    public void setTimestamp(int index, Timestamp x, Calendar cal) throws SQLException {
        set(index, x == null || cal == null ? x : (Object) local(x, cal));
    }

    private static LocalDateTime local(java.util.Date x, Calendar cal) {
        // java.sql.Date and Time don't support toInstant.
        ZoneId zone = cal.getTimeZone().toZoneId();
        LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(x.getTime()), zone);
        return x instanceof Timestamp ts ? t.withNano(ts.getNanos()) : t;
    }

    @Override
    public void setObject(int index, Object x) throws SQLException {
        set(index, x);
    }

    /** The value is sent as its Java type, and cast to the type it's used as. */
    @Override
    public void setObject(int index, Object x, int targetSqlType) throws SQLException {
        set(index, x);
    }

    /** The value is sent as its Java type, and cast to the type it's used as. */
    @Override
    public void setObject(int index, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        set(index, x);
    }
}

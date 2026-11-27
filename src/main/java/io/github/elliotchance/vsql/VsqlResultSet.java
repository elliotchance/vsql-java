package io.github.elliotchance.vsql;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The rows of a query, see {@link Statement#executeQuery(String)}. It's forward
 * only and read only. Its cursor is closed (which commits the statement, with
 * auto-commit on) after the last row, or when it's closed.
 */
public final class VsqlResultSet extends UnsupportedResultSet {
    private final VsqlStatement statement;
    private final Object lock;
    private final Column[] columns;
    // The rows of a result set that's made by the driver (rather than a
    // cursor), or null.
    private final Iterator<Object[]> rows;
    private final long maxRows;
    private int fetchSize;
    private boolean open = true;
    private boolean closed;
    private boolean done;
    private Object[] row;
    private long rowNumber;
    private boolean wasNull;
    // The first row, which isBeforeFirst fetches before next is called (it's
    // null if there are no rows), and next returns.
    private boolean lookedAhead;
    private Object[] ahead;

    VsqlResultSet(VsqlStatement statement, Column[] columns, long maxRows, int fetchSize) {
        this.statement = statement;
        this.lock = statement.connection;
        this.columns = columns;
        this.rows = null;
        this.maxRows = maxRows;
        this.fetchSize = fetchSize;
    }

    /**
     * A result set of rows in memory (such as DatabaseMetaData.getTypeInfo),
     * which doesn't have a statement. Each value is the Java type of its
     * column's kind (see Native.fetchRow).
     */
    VsqlResultSet(Column[] columns, List<Object[]> rows) {
        this.statement = null;
        this.lock = this;
        this.columns = columns;
        this.rows = rows.iterator();
        this.maxRows = 0;
    }

    private void check() throws SQLException {
        if (closed) {
            throw Errors.closed("result set");
        }
    }

    private void closeCursor() throws SQLException {
        if (open) {
            open = false;
            if (rows == null) {
                Native.closeCursor(statement.stmt);
            }
        }
    }

    /**
     * Fetches the next row, or null after the last row (or max rows), which
     * closes the cursor.
     */
    private Object[] fetch() throws SQLException {
        if (maxRows > 0 && rowNumber >= maxRows) {
            done = true;
            closeCursor();
            return null;
        }
        Object[] r;
        try {
            if (rows != null) {
                r = rows.hasNext() ? rows.next() : null;
            } else {
                r = Native.fetchRow(statement.stmt, columns);
            }
        } catch (SQLException e) {
            done = true;
            closeCursor();
            throw e;
        }
        if (r == null) {
            done = true;
            closeCursor();
        }
        return r;
    }

    @Override
    public boolean next() throws SQLException {
        synchronized (lock) {
            check();
            row = null;
            if (lookedAhead) {
                lookedAhead = false;
                row = ahead;
                ahead = null;
            } else if (!done) {
                row = fetch();
            }
            if (row == null) {
                return false;
            }
            rowNumber++;
            return true;
        }
    }

    /**
     * Whether next hasn't been called and there's at least one row. Before
     * next is called, it fetches the first row (which next returns), so it's
     * false for an empty result set, whose cursor is then closed (which
     * commits the statement, with auto-commit on).
     */
    @Override
    public boolean isBeforeFirst() throws SQLException {
        synchronized (lock) {
            check();
            if (rowNumber > 0) {
                return false;
            }
            if (!lookedAhead) {
                if (done) {
                    return false;
                }
                ahead = fetch();
                lookedAhead = true;
            }
            return ahead != null;
        }
    }

    /** Whether next has returned false after at least one row. */
    @Override
    public boolean isAfterLast() throws SQLException {
        synchronized (lock) {
            check();
            return done && row == null && rowNumber > 0;
        }
    }

    /** Whether the cursor is on the first row. */
    @Override
    public boolean isFirst() throws SQLException {
        synchronized (lock) {
            check();
            return row != null && rowNumber == 1;
        }
    }

    /** Closes the result set (and its cursor). It does nothing if it's closed. */
    @Override
    public void close() throws SQLException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            row = null;
            ahead = null;
            try {
                closeCursor();
            } finally {
                if (statement != null) {
                    statement.resultSetClosed(this);
                }
            }
        }
    }

    @Override
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** The raw value of a column (see Native.fetchRow), which sets wasNull. */
    private Object value(int index) throws SQLException {
        check();
        if (row == null) {
            throw new SQLException("vsql: the cursor isn't on a row", "24000");
        }
        if (index < 1 || index > columns.length) {
            throw new SQLException("vsql: invalid column index " + index, "07009");
        }
        Object v = row[index - 1];
        wasNull = v == null;
        return v;
    }

    private Column column(int index) {
        return columns[index - 1];
    }

    @Override
    public boolean wasNull() throws SQLException {
        check();
        return wasNull;
    }

    private static SQLException cantConvert(Column column, String to) {
        return new SQLException("vsql: a " + column.typeName() + " can't be read as a " + to, "22018");
    }

    @Override
    public String getString(int index) throws SQLException {
        Object v = value(index);
        return switch (v) {
            case null -> null;
            // A datetime or interval is read as its literal, such as
            // "TIMESTAMP '2022-01-02 15:04:05'".
            case String s when isDatetime(column(index).jdbcType()) || column(index).isInterval() ->
                    Values.unliteral(s);
            case byte[] b -> HexFormat.of().withUpperCase().formatHex(b);
            default -> v.toString();
        };
    }

    private static boolean isDatetime(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.DATE, java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE,
                    java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    @Override
    public String getNString(int index) throws SQLException {
        return getString(index);
    }

    @Override
    public boolean getBoolean(int index) throws SQLException {
        Object v = value(index);
        Column c = column(index);
        return switch (v) {
            case null -> false;
            case String s when c.jdbcType() == java.sql.Types.BOOLEAN -> s.equals("TRUE");
            case Long l -> l != 0;
            case Double d -> d != 0;
            case Float f -> f != 0;
            case String s when c.isNumber() -> new BigDecimal(s).signum() != 0;
            default -> throw cantConvert(c, "boolean");
        };
    }

    /** A number, as a BigDecimal (or null). */
    private BigDecimal decimal(int index, String to) throws SQLException {
        Object v = value(index);
        Column c = column(index);
        try {
            return switch (v) {
                case null -> null;
                case Long l -> BigDecimal.valueOf(l);
                case Double d -> new BigDecimal(Double.toString(d));
                case Float f -> new BigDecimal(Float.toString(f));
                case String s when c.isNumber() || c.isCharacter() -> new BigDecimal(s.trim());
                default -> throw cantConvert(c, to);
            };
        } catch (NumberFormatException e) {
            throw new SQLException("vsql: \"" + v + "\" isn't a number", "22018", e);
        }
    }

    /** A whole number in a range (a fraction is truncated). */
    private long whole(int index, String to, long min, long max) throws SQLException {
        Object v = value(index);
        if (v instanceof Long l && l >= min && l <= max) {
            return l;
        }
        BigDecimal d = decimal(index, to);
        if (d == null) {
            return 0;
        }
        BigInteger i = d.setScale(0, RoundingMode.DOWN).toBigInteger();
        if (i.compareTo(BigInteger.valueOf(min)) < 0 || i.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new SQLException("vsql: " + d + " is out of range of a " + to, "22003");
        }
        return i.longValue();
    }

    @Override
    public byte getByte(int index) throws SQLException {
        return (byte) whole(index, "byte", Byte.MIN_VALUE, Byte.MAX_VALUE);
    }

    @Override
    public short getShort(int index) throws SQLException {
        return (short) whole(index, "short", Short.MIN_VALUE, Short.MAX_VALUE);
    }

    @Override
    public int getInt(int index) throws SQLException {
        return (int) whole(index, "int", Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public long getLong(int index) throws SQLException {
        return whole(index, "long", Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Override
    public float getFloat(int index) throws SQLException {
        Object v = value(index);
        if (v instanceof Float f) {
            return f;
        }
        BigDecimal d = decimal(index, "float");
        return d == null ? 0 : d.floatValue();
    }

    @Override
    public double getDouble(int index) throws SQLException {
        Object v = value(index);
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Float f) {
            return f;
        }
        BigDecimal d = decimal(index, "double");
        return d == null ? 0 : d.doubleValue();
    }

    @Override
    public BigDecimal getBigDecimal(int index) throws SQLException {
        return decimal(index, "BigDecimal");
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(int index, int scale) throws SQLException {
        BigDecimal d = decimal(index, "BigDecimal");
        return d == null ? null : d.setScale(scale, RoundingMode.HALF_UP);
    }

    /** A BINARY, BINARY VARYING or BINARY LARGE OBJECT. */
    @Override
    public byte[] getBytes(int index) throws SQLException {
        return switch (value(index)) {
            case null -> null;
            case byte[] b -> b.clone();
            default -> throw cantConvert(column(index), "byte[]");
        };
    }

    @Override
    public InputStream getBinaryStream(int index) throws SQLException {
        byte[] b = getBytes(index);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    /** A BINARY LARGE OBJECT (or another binary string), as a copy in memory. */
    @Override
    public Blob getBlob(int index) throws SQLException {
        byte[] b = getBytes(index);
        return b == null ? null : new VsqlBlob(b);
    }

    /** A CHARACTER LARGE OBJECT (or another string), as a copy in memory. */
    @Override
    public Clob getClob(int index) throws SQLException {
        Object v = value(index);
        if (v != null && !column(index).isCharacter()) {
            throw cantConvert(column(index), "Clob");
        }
        return v == null ? null : new VsqlClob((String) v);
    }

    @Override
    public Reader getCharacterStream(int index) throws SQLException {
        String s = getString(index);
        return s == null ? null : new StringReader(s);
    }

    @Override
    public Reader getNCharacterStream(int index) throws SQLException {
        return getCharacterStream(index);
    }

    /** The string as US-ASCII (a character that isn't ASCII is a "?"). */
    @Override
    public InputStream getAsciiStream(int index) throws SQLException {
        String s = getString(index);
        return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * An interval as a Period (YEAR, MONTH or YEAR TO MONTH) or a Duration
     * (the others).
     */
    private Object interval(int index) throws SQLException {
        Object v = value(index);
        Column c = column(index);
        if (v == null) {
            return null;
        }
        if (!c.isInterval()) {
            throw cantConvert(c, "interval");
        }
        String s = Values.unliteral((String) v);
        return Interval.isYearMonth(c.dataType()) ? Interval.period(c.dataType(), s)
                : Interval.duration(c.dataType(), s);
    }

    /**
     * A DATE, TIME or TIMESTAMP as a LocalDate, LocalTime, OffsetTime,
     * LocalDateTime or OffsetDateTime (or null).
     */
    private Object temporal(int index) throws SQLException {
        Object v = value(index);
        if (v == null) {
            return null;
        }
        Column c = column(index);
        String s = (String) (v instanceof String ? v : null);
        return switch (c.jdbcType()) {
            case java.sql.Types.DATE -> Values.parseDate(s);
            case java.sql.Types.TIME -> Values.parseTime(s);
            case java.sql.Types.TIME_WITH_TIMEZONE -> Values.parseTimeWithTimeZone(s);
            case java.sql.Types.TIMESTAMP -> Values.parseTimestamp(s);
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> Values.parseTimestampWithTimeZone(s);
            default -> throw cantConvert(c, "date or time");
        };
    }

    /** A local date and time (a value with a time zone is in the zone). */
    private LocalDateTime localDateTime(int index, ZoneId zone, String to) throws SQLException {
        return switch (temporal(index)) {
            case null -> null;
            case LocalDate d -> d.atStartOfDay();
            case LocalDateTime t -> t;
            case OffsetDateTime t -> t.atZoneSameInstant(zone).toLocalDateTime();
            case LocalTime t -> LocalDate.EPOCH.atTime(t);
            case OffsetTime t -> LocalDate.EPOCH.atTime(t.toLocalTime());
            default -> throw cantConvert(column(index), to);
        };
    }

    @Override
    public Date getDate(int index) throws SQLException {
        Object t = temporal(index);
        if (t instanceof LocalTime || t instanceof OffsetTime) {
            throw cantConvert(column(index), "Date");
        }
        LocalDateTime d = localDateTime(index, ZoneId.systemDefault(), "Date");
        return d == null ? null : Date.valueOf(d.toLocalDate());
    }

    @Override
    public Time getTime(int index) throws SQLException {
        if (temporal(index) instanceof LocalDate) {
            throw cantConvert(column(index), "Time");
        }
        LocalDateTime t = localDateTime(index, ZoneId.systemDefault(), "Time");
        return t == null ? null : Time.valueOf(t.toLocalTime());
    }

    @Override
    public Timestamp getTimestamp(int index) throws SQLException {
        return switch (temporal(index)) {
            case null -> null;
            case OffsetDateTime t -> Timestamp.from(t.toInstant());
            default -> Timestamp.valueOf(localDateTime(index, ZoneId.systemDefault(), "Timestamp"));
        };
    }

    /** A value without a time zone is in the calendar's time zone. */
    private Instant instant(int index, Calendar cal, String to) throws SQLException {
        return switch (temporal(index)) {
            case null -> null;
            case OffsetDateTime t -> t.toInstant();
            default -> localDateTime(index, cal.getTimeZone().toZoneId(), to)
                    .atZone(cal.getTimeZone().toZoneId()).toInstant();
        };
    }

    @Override
    public Date getDate(int index, Calendar cal) throws SQLException {
        if (cal == null) {
            return getDate(index);
        }
        Instant i = instant(index, cal, "Date");
        return i == null ? null : new Date(i.toEpochMilli());
    }

    @Override
    public Time getTime(int index, Calendar cal) throws SQLException {
        if (cal == null) {
            return getTime(index);
        }
        Instant i = instant(index, cal, "Time");
        return i == null ? null : new Time(i.toEpochMilli());
    }

    @Override
    public Timestamp getTimestamp(int index, Calendar cal) throws SQLException {
        if (cal == null) {
            return getTimestamp(index);
        }
        Instant i = instant(index, cal, "Timestamp");
        return i == null ? null : Timestamp.from(i);
    }

    /**
     * The value as its Java type (see the package documentation): Integer,
     * Long, Float, Double, BigDecimal, String, byte[], Blob, Clob, Boolean,
     * java.sql.Date, java.sql.Time, OffsetTime, java.sql.Timestamp,
     * OffsetDateTime, Period or Duration.
     */
    @Override
    public Object getObject(int index) throws SQLException {
        Object v = value(index);
        if (v == null) {
            return null;
        }
        Column c = column(index);
        return switch (c.jdbcType()) {
            case java.sql.Types.SMALLINT, java.sql.Types.INTEGER -> ((Long) v).intValue();
            case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> getBigDecimal(index);
            case java.sql.Types.BOOLEAN -> getBoolean(index);
            case java.sql.Types.DATE -> getDate(index);
            case java.sql.Types.TIME -> getTime(index);
            case java.sql.Types.TIMESTAMP -> getTimestamp(index);
            case java.sql.Types.TIME_WITH_TIMEZONE, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> temporal(index);
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY -> getBytes(index);
            case java.sql.Types.BLOB -> getBlob(index);
            case java.sql.Types.CLOB -> getClob(index);
            default -> c.isInterval() ? interval(index) : v;
        };
    }

    /**
     * The value as a type: String, Boolean, Byte, Short, Integer, Long, Float,
     * Double, BigDecimal, BigInteger, byte[], Blob, Clob, LocalDate,
     * LocalTime, LocalDateTime, OffsetTime, OffsetDateTime, ZonedDateTime,
     * Instant, Period, Duration, java.sql.Date, java.sql.Time,
     * java.sql.Timestamp or Object. NULL is null.
     */
    @Override
    public <T> T getObject(int index, Class<T> type) throws SQLException {
        if (type == null) {
            throw new SQLException("vsql: the type can't be null", "HY009");
        }
        if (value(index) == null) {
            return null;
        }
        Column c = column(index);
        Object v;
        if (type == Object.class) {
            v = getObject(index);
        } else if (type == String.class) {
            v = getString(index);
        } else if (type == Boolean.class) {
            v = getBoolean(index);
        } else if (type == Byte.class) {
            v = getByte(index);
        } else if (type == Short.class) {
            v = getShort(index);
        } else if (type == Integer.class) {
            v = getInt(index);
        } else if (type == Long.class) {
            v = getLong(index);
        } else if (type == Float.class) {
            v = getFloat(index);
        } else if (type == Double.class) {
            v = getDouble(index);
        } else if (type == BigDecimal.class) {
            v = getBigDecimal(index);
        } else if (type == BigInteger.class) {
            v = getBigDecimal(index).toBigInteger();
        } else if (type == Date.class) {
            v = getDate(index);
        } else if (type == Time.class) {
            v = getTime(index);
        } else if (type == Timestamp.class) {
            v = getTimestamp(index);
        } else if (type == byte[].class) {
            v = getBytes(index);
        } else if (type == Blob.class) {
            v = getBlob(index);
        } else if (type == Clob.class) {
            v = getClob(index);
        } else if (type == Period.class || type == Duration.class) {
            v = interval(index);
            if (!type.isInstance(v)) {
                throw cantConvert(c, type.getName());
            }
        } else {
            Object t = temporal(index);
            v = switch (t) {
                case LocalDate d when type == LocalDate.class -> d;
                case LocalDateTime d when type == LocalDate.class -> d.toLocalDate();
                case LocalTime d when type == LocalTime.class -> d;
                case LocalDateTime d when type == LocalTime.class -> d.toLocalTime();
                case LocalDateTime d when type == LocalDateTime.class -> d;
                case LocalDate d when type == LocalDateTime.class -> d.atStartOfDay();
                case OffsetTime d when type == OffsetTime.class -> d;
                case OffsetDateTime d when type == OffsetDateTime.class -> d;
                case OffsetDateTime d when type == ZonedDateTime.class -> d.toZonedDateTime();
                case OffsetDateTime d when type == Instant.class -> d.toInstant();
                case OffsetDateTime d when type == OffsetTime.class -> d.toOffsetTime();
                case null, default -> throw cantConvert(c, type.getName());
            };
        }
        return type.cast(v);
    }

    /** Only an empty type map is supported. */
    @Override
    public Object getObject(int index, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw Errors.unsupported("a type map");
        }
        return getObject(index);
    }

    /** The index of the first column with the label (ignoring case). */
    @Override
    public int findColumn(String label) throws SQLException {
        check();
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].name().equalsIgnoreCase(label)) {
                return i + 1;
            }
        }
        throw new SQLException("vsql: there's no column " + label, "42S22");
    }

    @Override
    public String getString(String label) throws SQLException {
        return getString(findColumn(label));
    }

    @Override
    public String getNString(String label) throws SQLException {
        return getNString(findColumn(label));
    }

    @Override
    public boolean getBoolean(String label) throws SQLException {
        return getBoolean(findColumn(label));
    }

    @Override
    public byte getByte(String label) throws SQLException {
        return getByte(findColumn(label));
    }

    @Override
    public short getShort(String label) throws SQLException {
        return getShort(findColumn(label));
    }

    @Override
    public int getInt(String label) throws SQLException {
        return getInt(findColumn(label));
    }

    @Override
    public long getLong(String label) throws SQLException {
        return getLong(findColumn(label));
    }

    @Override
    public float getFloat(String label) throws SQLException {
        return getFloat(findColumn(label));
    }

    @Override
    public double getDouble(String label) throws SQLException {
        return getDouble(findColumn(label));
    }

    @Override
    public BigDecimal getBigDecimal(String label) throws SQLException {
        return getBigDecimal(findColumn(label));
    }

    @Deprecated
    @Override
    public BigDecimal getBigDecimal(String label, int scale) throws SQLException {
        return getBigDecimal(findColumn(label), scale);
    }

    @Override
    public byte[] getBytes(String label) throws SQLException {
        return getBytes(findColumn(label));
    }

    @Override
    public InputStream getBinaryStream(String label) throws SQLException {
        return getBinaryStream(findColumn(label));
    }

    @Override
    public Blob getBlob(String label) throws SQLException {
        return getBlob(findColumn(label));
    }

    @Override
    public Clob getClob(String label) throws SQLException {
        return getClob(findColumn(label));
    }

    @Override
    public Reader getCharacterStream(String label) throws SQLException {
        return getCharacterStream(findColumn(label));
    }

    @Override
    public Reader getNCharacterStream(String label) throws SQLException {
        return getNCharacterStream(findColumn(label));
    }

    @Override
    public InputStream getAsciiStream(String label) throws SQLException {
        return getAsciiStream(findColumn(label));
    }

    @Override
    public Date getDate(String label) throws SQLException {
        return getDate(findColumn(label));
    }

    @Override
    public Date getDate(String label, Calendar cal) throws SQLException {
        return getDate(findColumn(label), cal);
    }

    @Override
    public Time getTime(String label) throws SQLException {
        return getTime(findColumn(label));
    }

    @Override
    public Time getTime(String label, Calendar cal) throws SQLException {
        return getTime(findColumn(label), cal);
    }

    @Override
    public Timestamp getTimestamp(String label) throws SQLException {
        return getTimestamp(findColumn(label));
    }

    @Override
    public Timestamp getTimestamp(String label, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(label), cal);
    }

    @Override
    public Object getObject(String label) throws SQLException {
        return getObject(findColumn(label));
    }

    @Override
    public <T> T getObject(String label, Class<T> type) throws SQLException {
        return getObject(findColumn(label), type);
    }

    @Override
    public Object getObject(String label, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(label), map);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        check();
        return new VsqlResultSetMetaData(columns);
    }

    /** The statement, or null for a DatabaseMetaData result set. */
    @Override
    public Statement getStatement() throws SQLException {
        check();
        return statement;
    }

    /** The number of the current row (starting at 1), or 0 if there isn't one. */
    @Override
    public int getRow() throws SQLException {
        check();
        return row == null ? 0 : (int) rowNumber;
    }

    @Override
    public int getType() throws SQLException {
        check();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        check();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        check();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        check();
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        check();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw Errors.unsupported("a fetch direction other than FETCH_FORWARD");
        }
    }

    /** The fetch size is a hint, which vsql doesn't use. */
    @Override
    public void setFetchSize(int rows) throws SQLException {
        check();
        if (rows < 0) {
            throw new SQLException("vsql: the fetch size can't be negative", "HY024");
        }
        fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        check();
        return fetchSize;
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
        throw new SQLException("vsql: not a wrapper for " + iface.getName(), "HY000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}

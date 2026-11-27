package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Reading the values of a ResultSet as each Java type. */
class ResultSetTest {
    static final ZoneOffset PLUS_0530 = ZoneOffset.ofHoursMinutes(5, 30);
    static final OffsetDateTime TZ = OffsetDateTime.of(2022, 1, 2, 15, 4, 5, 0, PLUS_0530);

    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    /** The row of "VALUES (values)", which is closed with the connection. */
    ResultSet row(String values) throws SQLException {
        ResultSet rs = conn.createStatement().executeQuery("VALUES (" + values + ")");
        assertTrue(rs.next());
        return rs;
    }

    /** The label of a column. */
    static String c(ResultSet rs, int index) throws SQLException {
        return rs.getMetaData().getColumnLabel(index);
    }

    /** A row of a table with a binary string (and NULL) and the values. */
    ResultSet binaryRow(String values) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE b (x VARBINARY(5), y VARBINARY(5))");
        }
        try (var s = conn.prepareStatement("INSERT INTO b (x, y) VALUES (?, ?)")) {
            s.setBytes(1, new byte[] {'a', 'b'});
            s.setBytes(2, null);
            s.executeUpdate();
        }
        ResultSet rs = conn.createStatement().executeQuery("SELECT x, y, " + values + " FROM b");
        assertTrue(rs.next());
        return rs;
    }

    static void assertSqlState(String sqlstate, Executable e) {
        assertEquals(sqlstate, assertThrows(SQLException.class, e).getSQLState());
    }

    @Test
    void fetchFails() throws SQLException {
        VsqlStatement s = (VsqlStatement) conn.createStatement();
        ResultSet rs = s.executeQuery("VALUES 1, 2");
        int stmt = s.stmt;
        Native.freeHandle(Native.SQL_HANDLE_STMT, stmt);
        SQLException e = assertThrows(SQLException.class, rs::next);
        assertEquals(1, e.getSuppressed().length);
        s.stmt = 0;
    }

    /** A column that can't be read as its type is an error, and the cursor is closed. */
    @Test
    void readFails() throws SQLException {
        try (VsqlStatement s = (VsqlStatement) conn.createStatement()) {
            Native.execDirect(s.handle(), "VALUES 'abc'");
            Column[] columns = {new Column("X", Native.SQL_INTEGER, 0, (short) 0, true)};
            ResultSet rs = new VsqlResultSet(s, columns, ResultSet.TYPE_FORWARD_ONLY, 0, 0, ResultSet.FETCH_FORWARD);
            SQLException e = assertThrows(SQLException.class, rs::next);
            assertEquals(0, e.getSuppressed().length);
            assertFalse(rs.next());
        }
    }

    @Test
    void cursor() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM (VALUES 1) AS t (x) WHERE x = 2")) {
            assertFalse(rs.next());
            assertFalse(rs.isAfterLast());
        }

        ResultSet rs = row("1, 'a'");
        assertSqlState("07009", () -> rs.getString(0));
        assertSqlState("07009", () -> rs.getString(3));
        assertEquals("a", rs.getNString(2));
        assertEquals("a", rs.getNString(c(rs, 2)));
        rs.close();
        rs.close();
    }

    @Test
    void numbers() throws SQLException {
        ResultSet rs = row("TRUE, CAST(0 AS INTEGER), CAST(1.5 AS DOUBLE PRECISION), CAST(0 AS REAL), "
                + "CAST(2.5 AS NUMERIC(5, 1)), CAST('abc' AS VARCHAR(10)), CAST(1000 AS BIGINT), "
                + "CAST(-1000 AS BIGINT), CAST(NULL AS INTEGER), CAST(' 7 ' AS VARCHAR(10)), "
                + "CAST(0 AS DOUBLE PRECISION), CAST(1 AS REAL), CAST(0 AS NUMERIC(5, 1))");
        assertFalse(rs.getBoolean(11));
        assertTrue(rs.getBoolean(12));
        assertFalse(rs.getBoolean(13));
        assertTrue(rs.getBoolean(1));
        assertFalse(rs.getBoolean(2));
        assertTrue(rs.getBoolean(3));
        assertFalse(rs.getBoolean(4));
        assertTrue(rs.getBoolean(5));
        assertSqlState("22018", () -> rs.getBoolean(6));
        assertFalse(rs.getBoolean(9));

        assertEquals(new BigDecimal("1.5"), rs.getBigDecimal(3));
        assertEquals(new BigDecimal("0.0"), rs.getBigDecimal(4));
        assertEquals(new BigDecimal("2.5"), rs.getBigDecimal(5));
        assertEquals(new BigDecimal("7"), rs.getBigDecimal(10));
        assertSqlState("22018", () -> rs.getBigDecimal(1));
        assertSqlState("22018", () -> rs.getBigDecimal(6));

        assertEquals(0, rs.getByte(2));
        assertEquals(2, rs.getByte(5));
        assertSqlState("22003", () -> rs.getByte(7));
        assertSqlState("22003", () -> rs.getByte(8));
        assertEquals(0, rs.getInt(9));
        assertEquals(0, rs.getByte(c(rs, 2)));

        assertEquals(0f, rs.getFloat(4));
        assertEquals(1.5f, rs.getFloat(3));
        assertEquals(0f, rs.getFloat(9));
        assertEquals(1.5f, rs.getFloat(c(rs, 3)));
        assertEquals(1.5, rs.getDouble(3));
        assertEquals(0.0, rs.getDouble(4));
        assertEquals(2.5, rs.getDouble(5));
        assertEquals(0.0, rs.getDouble(9));
        assertEquals(1.5, rs.getDouble(c(rs, 3)));
    }

    @SuppressWarnings("deprecation")
    @Test
    void scaledDecimals() throws SQLException {
        ResultSet rs = row("CAST(2.55 AS NUMERIC(5, 2)), CAST(NULL AS INTEGER)");
        assertEquals(new BigDecimal("2.6"), rs.getBigDecimal(1, 1));
        assertEquals(new BigDecimal("2.6"), rs.getBigDecimal(c(rs, 1), 1));
        assertNull(rs.getBigDecimal(2, 1));
        assertEquals(new BigDecimal("2.55"), rs.getBigDecimal(c(rs, 1)));
    }

    @Test
    void strings() throws SQLException, IOException {
        ResultSet rs = binaryRow("CAST(1 AS INTEGER), CAST(NULL AS VARCHAR(5)), CAST('xy' AS VARCHAR(5))");
        try (InputStream in = rs.getBinaryStream(1)) {
            assertArrayEquals(new byte[] {'a', 'b'}, in.readAllBytes());
        }
        assertNull(rs.getBinaryStream(2));
        assertArrayEquals(new byte[] {'a', 'b'}, rs.getBytes(c(rs, 1)));
        assertArrayEquals(new byte[] {'a', 'b'}, rs.getBlob(c(rs, 1)).getBytes(1, 2));
        assertSqlState("22018", () -> rs.getClob(3));
        assertNull(rs.getClob(4));
        assertEquals("xy", rs.getClob(c(rs, 5)).getSubString(1, 2));
        try (Reader r = rs.getCharacterStream(c(rs, 5))) {
            assertEquals("xy", BlobClobTest.read(r));
        }
    }

    @Test
    void intervals() throws SQLException {
        ResultSet rs = row("INTERVAL '2' DAY, CAST(1 AS INTEGER)");
        assertEquals(Duration.ofDays(2), rs.getObject(1, Duration.class));
        assertSqlState("22018", () -> rs.getObject(1, Period.class));
        assertSqlState("22018", () -> rs.getObject(2, Duration.class));
    }

    @Test
    void datetimes() throws SQLException {
        ResultSet rs = row("DATE '2022-01-02', TIME '15:04:05', TIME '15:04:05+05:30', "
                + "TIMESTAMP '2022-01-02 15:04:05', TIMESTAMP '2022-01-02 15:04:05+05:30', CAST(NULL AS DATE), "
                + "CAST(NULL AS TIME), CAST(NULL AS TIMESTAMP)");
        assertSqlState("22018", () -> rs.getDate(2));
        assertSqlState("22018", () -> rs.getDate(3));
        assertEquals(Date.valueOf(TZ.atZoneSameInstant(TimeZone.getDefault().toZoneId()).toLocalDate()),
                rs.getDate(5));
        assertSqlState("22018", () -> rs.getTime(1));
        assertNull(rs.getTime(7));
        assertNotNull(rs.getTime(3));
        assertNull(rs.getTimestamp(8));
        assertEquals(Timestamp.from(TZ.toInstant()), rs.getTimestamp(5));
        assertEquals(Timestamp.valueOf("1970-01-01 15:04:05"), rs.getTimestamp(2));
        assertEquals(Timestamp.valueOf("2022-01-02 00:00:00"), rs.getTimestamp(1));

        assertEquals(Date.valueOf("2022-01-02"), rs.getDate(c(rs, 1)));
        assertEquals(Time.valueOf("15:04:05"), rs.getTime(c(rs, 2)));
        assertEquals(Timestamp.valueOf("2022-01-02 15:04:05"), rs.getTimestamp(c(rs, 4)));
    }

    @Test
    void calendars() throws SQLException {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        ResultSet rs = row("DATE '2022-01-02', TIME '15:04:05', TIME '15:04:05+05:30', "
                + "TIMESTAMP '2022-01-02 15:04:05', TIMESTAMP '2022-01-02 15:04:05+05:30', CAST(NULL AS DATE)");
        assertEquals(rs.getDate(1), rs.getDate(1, null));
        assertEquals(rs.getTime(2), rs.getTime(2, null));
        assertEquals(rs.getTimestamp(4), rs.getTimestamp(4, null));
        assertNull(rs.getDate(6, utc));
        assertNull(rs.getTime(6, utc));
        assertNull(rs.getTimestamp(6, utc));

        assertEquals(Instant.parse("2022-01-02T00:00:00Z").toEpochMilli(), rs.getDate(c(rs, 1), utc).getTime());
        assertEquals(Instant.parse("1970-01-01T15:04:05Z").toEpochMilli(), rs.getTime(c(rs, 2), utc).getTime());
        assertEquals(Instant.parse("1970-01-01T09:34:05Z").toEpochMilli(), rs.getTime(3, utc).getTime());
        assertEquals(Timestamp.from(Instant.parse("2022-01-02T15:04:05Z")), rs.getTimestamp(c(rs, 4), utc));
        assertEquals(Timestamp.from(TZ.toInstant()), rs.getTimestamp(5, utc));
    }

    @Test
    void objectsOfTypes() throws SQLException {
        ResultSet rs = binaryRow("CAST(7 AS INTEGER), CAST(NULL AS INTEGER)");
        assertSqlState("HY009", () -> rs.getObject(3, (Class<?>) null));
        assertNull(rs.getObject(4, String.class));
        assertEquals(7, rs.getObject(3, Object.class));
        assertEquals("7", rs.getObject(3, String.class));
        assertEquals(true, rs.getObject(3, Boolean.class));
        assertEquals((byte) 7, rs.getObject(3, Byte.class));
        assertEquals((short) 7, rs.getObject(3, Short.class));
        assertEquals(7, rs.getObject(3, Integer.class));
        assertEquals(7L, rs.getObject(3, Long.class));
        assertEquals(7f, rs.getObject(3, Float.class));
        assertEquals(7.0, rs.getObject(3, Double.class));
        assertEquals(new BigDecimal(7), rs.getObject(3, BigDecimal.class));
        assertEquals(BigInteger.valueOf(7), rs.getObject(3, BigInteger.class));
        assertArrayEquals(new byte[] {'a', 'b'}, rs.getObject(1, Blob.class).getBytes(1, 2));
        assertEquals(7, rs.getObject(c(rs, 3)));
        assertEquals(7L, rs.getObject(c(rs, 3), Long.class));
        assertEquals(7, rs.getObject(c(rs, 3), Map.of()));
        assertEquals(7, rs.getObject(3, (Map<String, Class<?>>) null));
        assertThrows(SQLFeatureNotSupportedException.class, () -> rs.getObject(3, Map.of("x", String.class)));
    }

    @Test
    void temporalsOfTypes() throws SQLException {
        ResultSet rs = row("DATE '2022-01-02', TIME '15:04:05', TIME '15:04:05+05:30', "
                + "TIMESTAMP '2022-01-02 15:04:05', TIMESTAMP '2022-01-02 15:04:05+05:30'");
        assertEquals(Date.valueOf("2022-01-02"), rs.getObject(1, Date.class));
        assertEquals(Timestamp.valueOf("2022-01-02 15:04:05"), rs.getObject(4, Timestamp.class));
        assertEquals(LocalDate.of(2022, 1, 2), rs.getObject(1, LocalDate.class));
        assertEquals(LocalTime.of(15, 4, 5), rs.getObject(2, LocalTime.class));
        assertEquals(LocalTime.of(15, 4, 5), rs.getObject(4, LocalTime.class));
        assertEquals(LocalDateTime.of(2022, 1, 2, 15, 4, 5), rs.getObject(4, LocalDateTime.class));
        assertEquals(LocalDateTime.of(2022, 1, 2, 0, 0), rs.getObject(1, LocalDateTime.class));
        assertEquals(OffsetTime.of(15, 4, 5, 0, PLUS_0530), rs.getObject(3, OffsetTime.class));
        assertNotNull(rs.getObject(3, LocalTime.class));
        assertEquals(TZ, rs.getObject(5, OffsetDateTime.class));
        assertEquals(TZ.toZonedDateTime(), rs.getObject(5, ZonedDateTime.class));
        assertEquals(TZ.toInstant(), rs.getObject(5, Instant.class));
        assertEquals(TZ.toOffsetTime(), rs.getObject(5, OffsetTime.class));
        assertSqlState("22018", () -> rs.getObject(2, LocalDate.class));
        assertSqlState("22018", () -> rs.getObject(4, OffsetTime.class));
        assertSqlState("22018", () -> rs.getObject(1, OffsetDateTime.class));
        assertSqlState("22018", () -> rs.getObject(3, LocalDate.class));
        assertSqlState("22018", () -> rs.getObject(5, LocalDate.class));
    }

    @Test
    void options() throws SQLException {
        ResultSet rs = row("1");
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, rs.getHoldability());
        assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
        rs.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertThrows(SQLFeatureNotSupportedException.class, () -> rs.setFetchDirection(ResultSet.FETCH_REVERSE));
        rs.setFetchSize(5);
        assertEquals(5, rs.getFetchSize());
        assertSqlState("HY024", () -> rs.setFetchSize(-1));
        assertNull(rs.getWarnings());
        rs.clearWarnings();
        DriverTest.assertWrapper(rs, VsqlResultSet.class);
    }
}

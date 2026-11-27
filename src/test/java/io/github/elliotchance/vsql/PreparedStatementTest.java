package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The parameters and execution of a PreparedStatement. */
class PreparedStatementTest {
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE foo (x INTEGER)");
        }
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    interface Setter {
        void set(PreparedStatement s) throws SQLException;
    }

    /** The value of a parameter cast to type. */
    Object value(String type, Setter setter) throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("VALUES CAST(? AS " + type + ")")) {
            setter.set(s);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1);
            }
        }
    }

    static void assertSqlState(String sqlstate, Executable e) {
        assertEquals(sqlstate, assertThrows(SQLException.class, e).getSQLState());
    }

    static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    static final InputStream BROKEN_STREAM = new InputStream() {
        @Override
        public int read() throws IOException {
            throw new IOException("broken");
        }
    };

    static final Reader BROKEN_READER = new Reader() {
        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            throw new IOException("broken");
        }

        @Override
        public void close() {
        }
    };

    @Test
    void setters() throws SQLException, IOException {
        URL url = URI.create("http://x").toURL();
        assertNull(value("INTEGER", s -> s.setNull(1, Types.INTEGER, "INTEGER")));
        assertEquals(1, value("INTEGER", s -> s.setByte(1, (byte) 1)));
        assertEquals(2, value("INTEGER", s -> s.setShort(1, (short) 2)));
        assertEquals(3L, value("BIGINT", s -> s.setLong(1, 3)));
        assertEquals(1.5, value("DOUBLE PRECISION", s -> s.setFloat(1, 1.5f)));
        assertEquals(2.5, value("DOUBLE PRECISION", s -> s.setDouble(1, 2.5)));
        assertEquals(new BigDecimal("1.25"), value("NUMERIC(5, 2)", s -> s.setBigDecimal(1, new BigDecimal("1.25"))));
        assertEquals("a", value("VARCHAR(10)", s -> s.setNString(1, "a")));
        assertEquals("http://x", value("VARCHAR(10)", s -> s.setURL(1, url)));
        assertNull(value("VARCHAR(10)", s -> s.setURL(1, null)));
        assertNull(value("VARCHAR(10)", s -> s.setObject(1, null, Types.VARCHAR, 0)));
        assertNull(value("VARCHAR(10)", s -> s.setObject(1, null, Types.VARCHAR)));
        assertEquals("ab", value("VARCHAR(10)", s -> s.setCharacterStream(1, new StringReader("abc"), 2)));
    }

    @SuppressWarnings("deprecation")
    @Test
    void streams() throws SQLException, IOException {
        assertArrayEquals(new byte[] {'a', 'b'}, ((java.sql.Blob) value("BLOB(10)",
                s -> s.setBlob(1, stream("abc"), 2))).getBytes(1, 10));
        assertNull(value("BLOB(10)", s -> s.setBlob(1, (InputStream) null, 2)));
        assertArrayEquals(new byte[] {'a'}, (byte[]) value("VARBINARY(10)", s -> s.setBinaryStream(1, stream("a"), 1)));
        assertArrayEquals(new byte[] {'a'}, (byte[]) value("VARBINARY(10)", s -> s.setBinaryStream(1, stream("a"))));
        assertNull(value("VARBINARY(10)", s -> s.setBinaryStream(1, null, 1L)));

        assertEquals("ab", value("VARCHAR(10)", s -> s.setCharacterStream(1, new StringReader("abc"), 2L)));
        assertEquals("ab", value("VARCHAR(10)", s -> s.setNCharacterStream(1, new StringReader("abc"), 2)));
        assertEquals("abc", value("VARCHAR(10)", s -> s.setNCharacterStream(1, new StringReader("abc"))));
        assertNull(value("VARCHAR(10)", s -> s.setClob(1, (Reader) null, 1)));
        assertEquals("ab", value("VARCHAR(10)", s -> s.setAsciiStream(1, stream("abc"), 2)));
        assertNull(value("VARCHAR(10)", s -> s.setAsciiStream(1, null, 1L)));
        assertEquals("ab", value("VARCHAR(10)", s -> s.setUnicodeStream(1, stream("abc"), 2)));
        assertNull(value("VARCHAR(10)", s -> s.setUnicodeStream(1, null, 2)));

        try (PreparedStatement s = conn.prepareStatement("VALUES CAST(? AS VARCHAR(10))")) {
            assertSqlState("22001", () -> s.setBinaryStream(1, stream("a"), Long.MAX_VALUE));
            assertSqlState("22023", () -> s.setBinaryStream(1, stream("a"), 2L));
            assertSqlState("HY000", () -> s.setBinaryStream(1, BROKEN_STREAM));
            assertSqlState("22001", () -> s.setCharacterStream(1, new StringReader("a"), Long.MAX_VALUE));
            assertSqlState("22023", () -> s.setCharacterStream(1, new StringReader("a"), 2L));
            assertSqlState("HY000", () -> s.setCharacterStream(1, BROKEN_READER));
        }
    }

    @Test
    void datetimes() throws SQLException {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Date date = Date.valueOf("2022-01-02");
        Time time = Time.valueOf("15:04:05");
        Timestamp ts = Timestamp.valueOf("2022-01-02 15:04:05.123456");

        assertEquals(date, value("DATE", s -> s.setDate(1, date)));
        assertEquals(time, value("TIME", s -> s.setTime(1, time)));
        assertEquals(ts, value("TIMESTAMP(6)", s -> s.setTimestamp(1, ts)));
        assertEquals(date, value("DATE", s -> s.setDate(1, date, null)));
        assertNull(value("DATE", s -> s.setDate(1, null, utc)));
        assertEquals(date.toLocalDate(), ((Date) value("DATE", s -> s.setDate(1, date,
                Calendar.getInstance(TimeZone.getDefault())))).toLocalDate());
        assertEquals(time, value("TIME", s -> s.setTime(1, time, null)));
        assertEquals(time.toLocalTime(), ((Time) value("TIME", s -> s.setTime(1, time,
                Calendar.getInstance(TimeZone.getDefault())))).toLocalTime());
        assertNull(value("TIME", s -> s.setTime(1, null, utc)));
        assertEquals(ts, value("TIMESTAMP(6)", s -> s.setTimestamp(1, ts, Calendar.getInstance(TimeZone.getDefault()))));
        assertEquals(ts, value("TIMESTAMP(6)", s -> s.setTimestamp(1, ts, null)));
        assertNull(value("TIMESTAMP(6)", s -> s.setTimestamp(1, null, utc)));
    }

    @Test
    void wrongKindOfStatement() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (x) VALUES (1)")) {
            assertSqlState("07000", s::executeQuery);
        }
        try (PreparedStatement s = conn.prepareStatement("VALUES 1")) {
            assertSqlState("07000", s::executeUpdate);
            assertSqlState("HY000", () -> s.execute("VALUES 1"));
            assertSqlState("HY000", () -> s.executeQuery("VALUES 1"));
            assertSqlState("HY000", () -> s.executeUpdate("VALUES 1"));
            assertSqlState("HY000", () -> s.addBatch("VALUES 1"));

            s.addBatch();
            BatchUpdateException e = assertThrows(BatchUpdateException.class, s::executeBatch);
            assertEquals("07000", e.getSQLState());
            assertEquals(0, e.getLargeUpdateCounts().length);
        }
    }

    @Test
    void batch() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (x) VALUES (CAST(? AS INTEGER))")) {
            s.setInt(1, 1);
            s.addBatch();
            s.clearBatch();
            assertEquals(0, s.executeBatch().length);

            s.setInt(1, 1);
            s.addBatch();
            s.setString(1, "x");
            s.addBatch();
            BatchUpdateException e = assertThrows(BatchUpdateException.class, s::executeBatch);
            assertArrayEquals(new long[] {1}, e.getLargeUpdateCounts());
            assertInstanceOf(SQLException.class, e.getCause());
        }
    }
}

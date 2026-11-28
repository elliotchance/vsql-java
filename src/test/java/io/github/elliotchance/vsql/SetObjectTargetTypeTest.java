package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The type of setObject(targetSqlType) and setNull(sqlType) (#21). */
class SetObjectTargetTypeTest {
    /** A connection to a new in-memory database. */
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    interface Setter {
        void set(PreparedStatement s) throws SQLException;
    }

    /** The value of "VALUES CAST(? AS type)" with the parameter set by set. */
    Object value(String type, Setter set) throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("VALUES CAST(? AS " + type + ")")) {
            set.set(s);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1);
            }
        }
    }

    /** The SQLSTATE of the exception that setting the parameter throws. */
    String error(Setter set) throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("VALUES CAST(? AS VARCHAR(10))")) {
            Executable e = () -> set.set(s);
            return assertThrows(SQLException.class, e).getSQLState();
        }
    }

    /** The value is converted to the type before it's cast to the parameter's. */
    @Test
    void converted() throws SQLException {
        // A DOUBLE PRECISION sent as an INTEGER.
        assertEquals("1", value("VARCHAR(10)", s -> s.setObject(1, 1.25, Types.INTEGER)));
        assertEquals("1", value("VARCHAR(10)", s -> s.setObject(1, 1.25, JDBCType.INTEGER)));

        // A timestamp sent as a DATE (or TIME).
        LocalDateTime t = LocalDateTime.of(2022, 1, 2, 3, 4, 5);
        assertEquals("DATE '2022-01-02'", value("VARCHAR(30)", s -> s.setObject(1, t, Types.DATE)));
        assertEquals(java.sql.Time.valueOf("03:04:05"), value("TIME", s -> s.setObject(1, t, Types.TIME)));
        assertEquals(java.sql.Timestamp.valueOf("2022-01-02 00:00:00"),
                value("TIMESTAMP", s -> s.setObject(1, LocalDate.of(2022, 1, 2), Types.TIMESTAMP)));
        assertEquals(java.sql.Timestamp.valueOf("2022-01-02 03:04:05"), value("TIMESTAMP",
                s -> s.setObject(1, OffsetDateTime.of(t, ZoneOffset.ofHours(5)), Types.TIMESTAMP)));

        // A string sent as a datetime is cast by vsql.
        assertEquals(java.sql.Date.valueOf("2024-01-02"),
                value("DATE", s -> s.setObject(1, "2024-01-02", Types.DATE)));
        assertEquals(java.sql.Date.valueOf("2024-01-02"),
                value("DATE", s -> s.setObject(1, "2024-01-02", JDBCType.DATE)));

        // A number sent as a BOOLEAN, and the other way around.
        assertEquals(true, value("BOOLEAN", s -> s.setObject(1, 1, Types.BOOLEAN)));
        assertEquals(false, value("BOOLEAN", s -> s.setObject(1, BigDecimal.ZERO, Types.BIT)));
        assertEquals(true, value("BOOLEAN", s -> s.setObject(1, "TRUE", Types.BOOLEAN)));
        assertEquals(1, value("INTEGER", s -> s.setObject(1, true, Types.INTEGER)));
        assertEquals(0L, value("BIGINT", s -> s.setObject(1, false, Types.BIGINT)));

        // A string sent as a number.
        assertEquals(12, value("INTEGER", s -> s.setObject(1, "12", Types.SMALLINT)));
        assertEquals(1.5, value("DOUBLE PRECISION", s -> s.setObject(1, "1.5", Types.DOUBLE)));

        // A value sent as a character string is its string.
        assertEquals("12", value("VARCHAR(10)", s -> s.setObject(1, 12, Types.VARCHAR)));
        assertEquals("1.50", value("VARCHAR(10)", s -> s.setObject(1, new BigDecimal("1.50"), Types.NVARCHAR)));
        assertEquals("2022-01-02", value("VARCHAR(10)",
                s -> s.setObject(1, LocalDate.of(2022, 1, 2), Types.LONGVARCHAR)));
        assertEquals("hello", value("VARCHAR(10)", s -> s.setObject(1, "hello", Types.CLOB)));

        // Binary strings.
        assertArrayEquals(new byte[] {1, 2}, (byte[]) value("BINARY VARYING(10)",
                s -> s.setObject(1, new byte[] {1, 2}, Types.BINARY)));
        assertArrayEquals(new byte[] {1, 2}, (byte[]) value("BINARY VARYING(10)",
                s -> s.setObject(1, new ByteArrayInputStream(new byte[] {1, 2}), Types.LONGVARBINARY)));

        // Types.OTHER (the type of an interval) and Types.NULL send the value
        // as its Java type.
        assertEquals(Period.of(1, 2, 0),
                value("INTERVAL YEAR TO MONTH", s -> s.setObject(1, Period.ofMonths(14), Types.OTHER)));
        assertEquals(5, value("INTEGER", s -> s.setObject(1, 5, Types.NULL)));
    }

    /** scaleOrLength is the scale of a DECIMAL or NUMERIC, rounded half up. */
    @Test
    void scale() throws SQLException {
        assertEquals(new BigDecimal("1.2600"),
                value("DECIMAL(10, 4)", s -> s.setObject(1, new BigDecimal("1.255"), Types.DECIMAL, 2)));
        assertEquals("1.26", value("VARCHAR(10)", s -> s.setObject(1, new BigDecimal("1.255"), Types.NUMERIC, 2)));
        assertEquals("-1.26", value("VARCHAR(10)", s -> s.setObject(1, "-1.255", JDBCType.NUMERIC, 2)));
        assertEquals("2", value("VARCHAR(10)", s -> s.setObject(1, 1.5, Types.DECIMAL, 0)));
        assertEquals("3.000", value("VARCHAR(10)", s -> s.setObject(1, 3, Types.DECIMAL, 3)));
        // Without a scale, the value keeps its own.
        assertEquals("1.255", value("VARCHAR(10)", s -> s.setObject(1, new BigDecimal("1.255"), Types.DECIMAL)));

        assertEquals("HY104", error(s -> s.setObject(1, BigDecimal.ONE, Types.DECIMAL, -1)));
        assertEquals("22018", error(s -> s.setObject(1, "abc", Types.DECIMAL)));
    }

    /** scaleOrLength is the length of a string type, or of a stream. */
    @Test
    void length() throws SQLException {
        assertEquals("ab   ", value("VARCHAR(10)", s -> s.setObject(1, "ab", Types.CHAR, 5)));
        assertEquals("hel", value("VARCHAR(10)", s -> s.setObject(1, new StringReader("hello"), Types.VARCHAR, 3)));
        assertEquals("hello", value("VARCHAR(10)", s -> s.setObject(1, new StringReader("hello"), Types.VARCHAR)));
        assertArrayEquals(new byte[] {1, 2}, (byte[]) value("BINARY VARYING(10)",
                s -> s.setObject(1, new ByteArrayInputStream(new byte[] {1, 2, 3}), Types.VARBINARY, 2)));
    }

    /** setNull has the type. */
    @Test
    void nulls() throws SQLException {
        int[] types = {Types.BOOLEAN, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.DOUBLE,
            Types.DECIMAL, Types.NUMERIC, Types.CHAR, Types.VARCHAR, Types.CLOB, Types.DATE, Types.TIMESTAMP,
            Types.NULL, Types.OTHER};
        for (int type : types) {
            assertNull(value("VARCHAR(10)", s -> s.setNull(1, type)), JDBCType.valueOf(type).getName());
            assertNull(value("VARCHAR(10)", s -> s.setNull(1, type, "IGNORED")), JDBCType.valueOf(type).getName());
            assertNull(value("VARCHAR(10)", s -> s.setObject(1, null, type)), JDBCType.valueOf(type).getName());
        }
        assertNull(value("INTEGER", s -> s.setNull(1, Types.INTEGER)));
        assertNull(value("DATE", s -> s.setNull(1, Types.DATE)));
        assertNull(value("TIME WITH TIME ZONE", s -> s.setNull(1, Types.TIME_WITH_TIMEZONE)));
        assertNull(value("TIMESTAMP WITH TIME ZONE", s -> s.setNull(1, Types.TIMESTAMP_WITH_TIMEZONE)));
        assertNull(value("DECIMAL(10, 2)", s -> s.setObject(1, null, Types.DECIMAL, 2)));
        assertNull(value("BINARY VARYING(10)", s -> s.setNull(1, Types.VARBINARY)));
        assertNull(value("BINARY LARGE OBJECT", s -> s.setNull(1, Types.BLOB)));
    }

    /** A value that can't be converted to the type is 07006. */
    @Test
    void cantConvert() throws SQLException {
        assertEquals("07006", error(s -> s.setObject(1, new byte[] {1}, Types.INTEGER)));
        assertEquals("07006", error(s -> s.setObject(1, new byte[] {1}, Types.VARCHAR)));
        assertEquals("07006", error(s -> s.setObject(1, "ab", Types.VARBINARY)));
        assertEquals("07006", error(s -> s.setObject(1, 5, Types.DATE)));
        assertEquals("07006", error(s -> s.setObject(1, true, Types.TIMESTAMP)));
        assertEquals("07006", error(s -> s.setObject(1, LocalTime.NOON, Types.DATE)));
        assertEquals("07006", error(s -> s.setObject(1, LocalDate.of(2022, 1, 2), Types.TIME)));
        assertEquals("07006", error(s -> s.setObject(1, new Object(), Types.INTEGER)));
    }

    /** A type that vsql doesn't have isn't supported. */
    @Test
    void unsupported() throws SQLException {
        int[] types = {Types.ARRAY, Types.STRUCT, Types.REF, Types.JAVA_OBJECT, Types.DISTINCT, Types.DATALINK,
            Types.ROWID, Types.SQLXML, Types.NCLOB, Types.REF_CURSOR, 12345};
        for (int type : types) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> value("VARCHAR(10)", s -> s.setNull(1, type)),
                    "type " + type);
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> value("VARCHAR(10)", s -> s.setObject(1, "a", type)), "type " + type);
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> value("VARCHAR(10)", s -> s.setObject(1, "a", type, 1)), "type " + type);
        }

        // Only a JDBCType is an SQLType that's supported.
        SQLType vendor = new SQLType() {
            @Override
            public String getName() {
                return "THING";
            }

            @Override
            public String getVendor() {
                return "example";
            }

            @Override
            public Integer getVendorTypeNumber() {
                return Types.INTEGER;
            }
        };
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> value("VARCHAR(10)", s -> s.setObject(1, "a", vendor)));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> value("VARCHAR(10)", s -> s.setObject(1, "a", JDBCType.ARRAY)));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> value("VARCHAR(10)", s -> s.setObject(1, "a", JDBCType.NCLOB, 1)));
        assertEquals("HY009", error(s -> s.setObject(1, "a", (SQLType) null)));
    }
}

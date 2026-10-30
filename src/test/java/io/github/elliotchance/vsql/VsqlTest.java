package io.github.elliotchance.vsql;

// The tests are ported from vsql-python and vsql-go.

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VsqlTest {
    private static final ZoneOffset PLUS_0530 = ZoneOffset.ofHoursMinutes(5, 30);
    private static final ZoneOffset MINUS_4 = ZoneOffset.ofHours(-4);

    @TempDir
    Path tmp;

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

    String url() {
        return "jdbc:vsql:" + tmp.resolve("test.vsql");
    }

    void exec(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    Object scalar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                return rs.getObject(1);
            }
        }
    }

    static Stream<Arguments> columnValues() {
        return Stream.of(
                Arguments.of("CAST(12 AS SMALLINT)", 12, Types.SMALLINT, "SMALLINT"),
                Arguments.of("CAST(-12 AS INTEGER)", -12, Types.INTEGER, "INTEGER"),
                Arguments.of("CAST(9223372036854775807 AS BIGINT)", Long.MAX_VALUE, Types.BIGINT, "BIGINT"),
                Arguments.of("CAST(1.5 AS REAL)", 1.5f, Types.REAL, "REAL"),
                Arguments.of("CAST(1.25 AS DOUBLE PRECISION)", 1.25, Types.DOUBLE, "DOUBLE PRECISION"),
                Arguments.of("CAST(1.5 AS DECIMAL(10, 3))", new BigDecimal("1.500"), Types.DECIMAL, "DECIMAL"),
                Arguments.of("CAST(12 AS NUMERIC(10, 2))", new BigDecimal("12.00"), Types.NUMERIC, "NUMERIC"),
                Arguments.of("CAST('ab' AS CHARACTER(4))", "ab  ", Types.CHAR, "CHARACTER"),
                Arguments.of("CAST('héllo' AS VARCHAR(10))", "héllo", Types.VARCHAR, "CHARACTER VARYING"),
                Arguments.of("TRUE", true, Types.BOOLEAN, "BOOLEAN"),
                Arguments.of("FALSE", false, Types.BOOLEAN, "BOOLEAN"),
                Arguments.of("DATE '2022-01-02'", java.sql.Date.valueOf("2022-01-02"), Types.DATE, "DATE"),
                Arguments.of("TIME '15:04:05'", java.sql.Time.valueOf("15:04:05"), Types.TIME, "TIME"),
                Arguments.of("TIME '15:04:05+05:30'", OffsetTime.of(15, 4, 5, 0, PLUS_0530),
                        Types.TIME_WITH_TIMEZONE, "TIME WITH TIME ZONE"),
                Arguments.of("TIMESTAMP '2022-01-02 15:04:05.5'",
                        Timestamp.valueOf("2022-01-02 15:04:05.5"), Types.TIMESTAMP, "TIMESTAMP"),
                Arguments.of("TIMESTAMP '2022-01-02 15:04:05.123+05:30'",
                        OffsetDateTime.of(2022, 1, 2, 15, 4, 5, 123_000_000, PLUS_0530),
                        Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE"),
                Arguments.of("CAST(NULL AS INTEGER)", null, Types.INTEGER, "INTEGER"),
                Arguments.of("CAST(NULL AS BOOLEAN)", null, Types.BOOLEAN, "BOOLEAN"),
                Arguments.of("CAST(NULL AS DATE)", null, Types.DATE, "DATE"));
    }

    @ParameterizedTest
    @MethodSource
    void columnValues(String expr, Object want, int type, String typeName) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("VALUES " + expr)) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(type, md.getColumnType(1));
            assertEquals(typeName, md.getColumnTypeName(1));
            assertTrue(rs.next());
            Object got = rs.getObject(1);
            assertEquals(want, got);
            if (want != null) {
                assertEquals(want.getClass(), got.getClass());
                assertEquals(want.getClass().getName(), md.getColumnClassName(1));
            }
            assertEquals(want == null, rs.wasNull());
            assertFalse(rs.next());
        }
    }

    /**
     * vsql drops the leading zeros of fractional seconds
     * (https://github.com/elliotchance/vsql/issues/697). This test fails once
     * it's fixed, so the assertThrows can be removed when the amalgamation is
     * updated.
     */
    @Test
    void fractionalSecondsWithLeadingZeros() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES TIMESTAMP '2022-01-02 15:04:05.000006'")) {
            rs.next();
            LocalDateTime got = rs.getObject(1, LocalDateTime.class);
            assertThrows(AssertionError.class, () -> assertEquals(6_000, got.getNano(), "vsql#697"));
        }
    }

    @Test
    void getters() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES ROW(CAST(12 AS SMALLINT), CAST(1.5 AS NUMERIC(5, 2)), "
                        + "'42', TIMESTAMP '2022-01-02 15:04:05.123456', CAST(100000 AS INTEGER), "
                        + "TIMESTAMP '2022-01-02 15:04:05+05:30', TIME '15:04:05.123456')")) {
            assertTrue(rs.next());
            assertEquals(12, rs.getShort(1));
            assertEquals(12L, rs.getLong("COL1"));
            assertEquals("12", rs.getString(1));
            assertEquals(12.0, rs.getDouble(1));
            assertEquals(new BigDecimal("1.50"), rs.getBigDecimal(2));
            assertEquals(1.5, rs.getDouble(2));
            assertEquals(1, rs.getInt(2));
            assertEquals("1.50", rs.getString(2));
            assertEquals(42, rs.getInt(3));
            assertEquals("2022-01-02 15:04:05.123456", rs.getString(4));
            assertEquals(LocalDateTime.of(2022, 1, 2, 15, 4, 5, 123_456_000),
                    rs.getObject(4, LocalDateTime.class));
            assertEquals(LocalDate.of(2022, 1, 2), rs.getObject(4, LocalDate.class));
            assertEquals(java.sql.Date.valueOf("2022-01-02"), rs.getDate(4));
            assertEquals(OffsetDateTime.of(2022, 1, 2, 15, 4, 5, 0, PLUS_0530).toInstant(),
                    rs.getObject(6, Instant.class));
            assertEquals(Timestamp.from(OffsetDateTime.of(2022, 1, 2, 15, 4, 5, 0, PLUS_0530).toInstant()),
                    rs.getTimestamp(6));
            assertEquals(LocalTime.of(15, 4, 5, 123_456_000), rs.getObject(7, LocalTime.class));

            // Out of range, and the wrong type.
            assertEquals("22003", assertThrows(SQLException.class, () -> rs.getShort(5)).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class, () -> rs.getDate(1)).getSQLState());
            assertEquals("22018",
                    assertThrows(SQLException.class, () -> rs.getObject(1, LocalDate.class)).getSQLState());

            // Column labels and indexes.
            assertEquals(2, rs.findColumn("col2"));
            assertEquals("42S22", assertThrows(SQLException.class, () -> rs.getInt("nope")).getSQLState());
            assertEquals("07009", assertThrows(SQLException.class, () -> rs.getInt(8)).getSQLState());
        }
    }

    @Test
    void nulls() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES ROW(CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR(10)))")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
            assertTrue(rs.wasNull());
            assertNull(rs.getObject(1, Integer.class));
            assertNull(rs.getString(2));
            assertTrue(rs.wasNull());
            assertFalse(rs.getBoolean(1));
        }
    }

    @Test
    void metadata() throws SQLException {
        exec("CREATE TABLE foo (a INTEGER NOT NULL, b VARCHAR(20), c NUMERIC(10, 3))");
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM foo")) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(3, md.getColumnCount());
            assertEquals("A", md.getColumnName(1));
            assertEquals("B", md.getColumnLabel(2));
            assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(1));
            assertEquals(ResultSetMetaData.columnNullable, md.isNullable(2));
            assertEquals(20, md.getPrecision(2));
            assertEquals(10, md.getPrecision(3));
            assertEquals(3, md.getScale(3));
            assertTrue(md.isSigned(1));
            assertFalse(md.isSigned(2));
        }

        // A prepared query's columns, before it's executed.
        try (PreparedStatement s = conn.prepareStatement("SELECT a, c FROM foo")) {
            ResultSetMetaData md = s.getMetaData();
            assertEquals(2, md.getColumnCount());
            assertEquals(Types.NUMERIC, md.getColumnType(2));
        }
        try (PreparedStatement s = conn.prepareStatement("DELETE FROM foo")) {
            assertNull(s.getMetaData());
        }

        DatabaseMetaData db = conn.getMetaData();
        assertEquals("vsql", db.getDatabaseProductName());
        assertEquals(1, db.getDatabaseMajorVersion());
        assertEquals("jdbc:vsql::memory:", db.getURL());
        assertTrue(db.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED));
        assertFalse(db.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));
        assertThrows(SQLFeatureNotSupportedException.class, () -> db.getTables(null, null, null, null));
    }

    static Stream<Arguments> parameters() {
        LocalDateTime ts = LocalDateTime.of(2022, 1, 2, 15, 4, 5, 123_456_000);
        return Stream.of(
                Arguments.of("s", 12, 12),
                Arguments.of("i", -12, -12),
                Arguments.of("b", Long.MAX_VALUE, Long.MAX_VALUE),
                Arguments.of("r", 1.5f, 1.5f),
                Arguments.of("d", 1.25, 1.25),
                Arguments.of("n", 7, new BigDecimal("7.00000")),
                Arguments.of("n", 1.5, new BigDecimal("1.50000")),
                Arguments.of("n", new BigDecimal("-1.25"), new BigDecimal("-1.25000")),
                Arguments.of("n", new BigDecimal("1E+2"), new BigDecimal("100.00000")),
                // vsql counts a digit before the decimal point (vsql-go#3).
                Arguments.of("n", new BigDecimal("0.25"), new BigDecimal("0.25000")),
                Arguments.of("n", new BigDecimal("-0.00005"), new BigDecimal("-0.00005")),
                Arguments.of("n", BigDecimal.ZERO, new BigDecimal("0.00000")),
                Arguments.of("n", new BigInteger("18446744073709551615"),
                        new BigDecimal("18446744073709551615.00000")),
                Arguments.of("c", "héllo", "héllo"),
                Arguments.of("c", "", ""),
                Arguments.of("bo", true, true),
                Arguments.of("bo", false, false),
                Arguments.of("ts", ts, Timestamp.valueOf(ts)),
                Arguments.of("ts", Timestamp.valueOf(ts), Timestamp.valueOf(ts)),
                Arguments.of("tz", ts.atOffset(MINUS_4), ts.atOffset(MINUS_4)),
                Arguments.of("tz", ts.atOffset(MINUS_4).toInstant(),
                        ts.atOffset(MINUS_4).withOffsetSameInstant(ZoneOffset.UTC)),
                Arguments.of("dt", LocalDate.of(2022, 1, 2), java.sql.Date.valueOf("2022-01-02")),
                Arguments.of("dt", java.sql.Date.valueOf("2022-01-02"), java.sql.Date.valueOf("2022-01-02")),
                Arguments.of("tm", LocalTime.of(15, 4, 5), java.sql.Time.valueOf("15:04:05")),
                Arguments.of("tt", OffsetTime.of(15, 4, 5, 0, PLUS_0530), OffsetTime.of(15, 4, 5, 0, PLUS_0530)),
                Arguments.of("i", null, null),
                Arguments.of("c", null, null),
                Arguments.of("bo", null, null),
                Arguments.of("dt", null, null));
    }

    @ParameterizedTest
    @MethodSource
    void parameters(String column, Object param, Object want) throws SQLException {
        exec("""
                CREATE TABLE foo (
                    s SMALLINT, i INTEGER, b BIGINT, r REAL, d DOUBLE PRECISION,
                    n NUMERIC(30, 5), c VARCHAR(100), bo BOOLEAN,
                    ts TIMESTAMP(6), tz TIMESTAMP(6) WITH TIME ZONE, dt DATE,
                    tm TIME(6), tt TIME(6) WITH TIME ZONE)""");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (" + column + ") VALUES (?)")) {
            s.setObject(1, param);
            assertEquals(1, s.executeUpdate());
        }
        assertEquals(want, scalar(conn, "SELECT " + column + " FROM foo"));
    }

    @Test
    void typedSetters() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement(
                "VALUES ROW(CAST(? AS INTEGER), CAST(? AS VARCHAR(10)), CAST(? AS BOOLEAN), CAST(? AS DATE))")) {
            s.setInt(1, 5);
            s.setString(2, "a");
            s.setBoolean(3, true);
            s.setNull(4, Types.DATE);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
                assertEquals("a", rs.getString(2));
                assertTrue(rs.getBoolean(3));
                assertNull(rs.getDate(4));
            }
        }
    }

    @Test
    void parameterErrors() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("VALUES CAST(? AS INTEGER)")) {
            // Not set.
            assertEquals("07001", assertThrows(SQLException.class, s::executeQuery).getSQLState());
            // A gap.
            s.setInt(2, 1);
            assertEquals("07002", assertThrows(SQLException.class, s::executeQuery).getSQLState());
            s.clearParameters();

            assertThrows(SQLFeatureNotSupportedException.class, () -> s.setObject(1, new byte[] {1}));
            assertEquals("07006",
                    assertThrows(SQLException.class, () -> s.setObject(1, new Object())).getSQLState());
            assertEquals("07009", assertThrows(SQLException.class, () -> s.setInt(0, 1)).getSQLState());
            // A PreparedStatement can't execute other SQL.
            assertThrows(SQLException.class, () -> s.executeQuery("VALUES 1"));
        }

        // Fewer parameters than the last execution is an error, rather than
        // reading the old ones (vsql-go#4).
        try (PreparedStatement s = conn.prepareStatement(
                "VALUES ROW(CAST(? AS VARCHAR(10)), CAST(? AS VARCHAR(10)))")) {
            s.setString(1, "a");
            s.setString(2, "b");
            s.executeQuery().close();
            s.clearParameters();
            s.setString(1, "a");
            assertEquals("07001", assertThrows(SQLException.class, s::executeQuery).getSQLState());
        }
    }

    @Test
    void longStrings() throws SQLException {
        exec("CREATE TABLE foo (s VARCHAR(10000))");
        for (int n : new int[] {255, 256, 257, 1000, 9999}) {
            // Multi-byte characters cross the boundary of each piece that
            // SQLGetData reads.
            String want = "é".repeat(n / 2) + "x".repeat(n % 2);
            exec("DELETE FROM foo");
            try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (s) VALUES (?)")) {
                s.setString(1, want);
                s.executeUpdate();
            }
            assertEquals(want, scalar(conn, "SELECT s FROM foo"), "length " + n);
        }
    }

    @Test
    void errors() throws SQLException {
        record Case(String sql, Class<? extends SQLException> type, String sqlstate) {
        }
        for (Case c : List.of(
                new Case("SELECT * FROM nope", SQLSyntaxErrorException.class, "42P01"),
                new Case("SELEC 1", SQLSyntaxErrorException.class, "42601"),
                // Transactions are ended with commit and rollback.
                new Case("COMMIT", SQLSyntaxErrorException.class, "42000"),
                new Case("VALUES 1 / 0", SQLDataException.class, "22012"))) {
            SQLException e = assertThrows(SQLException.class, () -> exec(c.sql()), c.sql());
            assertInstanceOf(c.type(), e, c.sql());
            assertEquals(c.sqlstate(), e.getSQLState(), c.sql());

            // The connection still works after an error.
            assertEquals(1, scalar(conn, "VALUES 1"));
        }

        assertThrows(SQLSyntaxErrorException.class, () -> conn.prepareStatement("SELEC 1"));

        exec("CREATE TABLE foo (x INTEGER NOT NULL)");
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> exec("INSERT INTO foo (x) VALUES (NULL)"));
    }

    @Test
    void updateCounts() throws SQLException {
        exec("CREATE TABLE foo (x INTEGER)");
        try (Statement s = conn.createStatement()) {
            assertEquals(1, s.executeUpdate("INSERT INTO foo (x) VALUES (1)"));
            assertFalse(s.execute("INSERT INTO foo (x) VALUES (2)"));
            assertEquals(1, s.getUpdateCount());
            assertNull(s.getResultSet());
            assertEquals(2, s.executeUpdate("UPDATE foo SET x = x + 1"));

            assertTrue(s.execute("SELECT * FROM foo"));
            assertEquals(-1, s.getUpdateCount());
            ResultSet rs = s.getResultSet();
            assertFalse(s.getMoreResults());
            assertTrue(rs.isClosed());
            assertEquals(-1, s.getUpdateCount());

            // executeQuery and executeUpdate check the kind of statement.
            assertThrows(SQLException.class, () -> s.executeQuery("DELETE FROM foo WHERE x > 100"));
            assertThrows(SQLException.class, () -> s.executeUpdate("SELECT * FROM foo"));
            s.execute("DROP TABLE foo");
        }
    }

    @Test
    void resultSets() throws SQLException {
        exec("CREATE TABLE foo (x INTEGER)");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (x) VALUES (?)")) {
            for (int i = 1; i <= 10; i++) {
                s.setInt(1, i);
                s.addBatch();
            }
            assertArrayEquals(new int[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, s.executeBatch());
        }

        // Each statement has its own cursor, so the rows of one are still open
        // while another is executed.
        try (PreparedStatement s1 = conn.prepareStatement("SELECT x FROM foo WHERE x > ?");
                PreparedStatement s2 = conn.prepareStatement("SELECT x FROM foo WHERE x > ?")) {
            s1.setInt(1, 5);
            s2.setInt(1, 8);
            ResultSet rs1 = s1.executeQuery();
            ResultSet rs2 = s2.executeQuery();
            assertEquals(2, count(rs2));
            assertEquals(5, count(rs1));

            // Executing again closes the result set.
            ResultSet first = s1.executeQuery();
            assertTrue(first.next());
            assertEquals(1, first.getRow());
            ResultSet second = s1.executeQuery();
            assertTrue(first.isClosed());
            assertEquals(5, count(second));
            assertFalse(second.next());
            assertEquals(0, second.getRow());
            assertEquals("24000", assertThrows(SQLException.class, () -> second.getInt(1)).getSQLState());
        }

        // Max rows.
        try (Statement s = conn.createStatement()) {
            s.setMaxRows(3);
            assertEquals(3, count(s.executeQuery("SELECT x FROM foo")));
        }

        // Closing the statement closes its result set.
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT x FROM foo");
        s.close();
        assertTrue(rs.isClosed());
        assertThrows(SQLException.class, rs::next);
        assertThrows(SQLException.class, () -> s.execute("VALUES 1"));

        // Close on completion.
        try (Statement c = conn.createStatement()) {
            c.closeOnCompletion();
            ResultSet r = c.executeQuery("SELECT x FROM foo");
            count(r);
            assertFalse(c.isClosed());
            r.close();
            assertTrue(c.isClosed());
        }
    }

    static int count(ResultSet rs) throws SQLException {
        int n = 0;
        while (rs.next()) {
            n++;
        }
        return n;
    }

    @Test
    void batches() throws SQLException {
        exec("CREATE TABLE foo (x INTEGER NOT NULL)");
        try (Statement s = conn.createStatement()) {
            s.addBatch("INSERT INTO foo (x) VALUES (1)");
            s.addBatch("INSERT INTO foo (x) VALUES (2)");
            s.addBatch("UPDATE foo SET x = x + 1");
            assertArrayEquals(new int[] {1, 1, 2}, s.executeBatch());

            s.addBatch("INSERT INTO foo (x) VALUES (3)");
            s.addBatch("INSERT INTO foo (x) VALUES (NULL)");
            s.addBatch("INSERT INTO foo (x) VALUES (4)");
            BatchUpdateException e = assertThrows(BatchUpdateException.class, s::executeBatch);
            assertArrayEquals(new long[] {1}, e.getLargeUpdateCounts());
            assertInstanceOf(SQLIntegrityConstraintViolationException.class, e.getCause());

            // A batch is cleared when it's executed.
            assertArrayEquals(new int[0], s.executeBatch());
        }
    }

    @Test
    void transactions() throws SQLException {
        try (Connection c = DriverManager.getConnection(url())) {
            c.createStatement().execute("CREATE TABLE foo (x INTEGER)");
        }

        try (Connection c = DriverManager.getConnection(url());
                Connection other = DriverManager.getConnection(url())) {
            assertTrue(c.getAutoCommit());
            Statement s = c.createStatement();

            // Auto-commit: another connection sees it.
            s.execute("INSERT INTO foo (x) VALUES (1)");
            assertEquals(1, scalar(other, "SELECT COUNT(*) FROM foo"));
            assertThrows(SQLException.class, c::commit);

            // Commit.
            c.setAutoCommit(false);
            s.execute("INSERT INTO foo (x) VALUES (2)");
            assertEquals(1, scalar(other, "SELECT COUNT(*) FROM foo"));
            c.commit();
            assertEquals(2, scalar(other, "SELECT COUNT(*) FROM foo"));

            // Rollback.
            s.execute("INSERT INTO foo (x) VALUES (3)");
            assertEquals(3, scalar(c, "SELECT COUNT(*) FROM foo"));
            c.rollback();
            assertEquals(2, scalar(other, "SELECT COUNT(*) FROM foo"));

            // Commit and rollback without a transaction do nothing.
            c.commit();
            c.rollback();

            // Committing closes the result sets.
            ResultSet rs = s.executeQuery("SELECT x FROM foo");
            c.commit();
            assertTrue(rs.isClosed());

            // Turning auto-commit on commits the transaction.
            s.execute("INSERT INTO foo (x) VALUES (4)");
            c.setAutoCommit(true);
            assertEquals(3, scalar(other, "SELECT COUNT(*) FROM foo"));

            // Closing the connection rolls back the transaction.
            c.setAutoCommit(false);
            s.execute("INSERT INTO foo (x) VALUES (5)");
        }
        try (Connection c = DriverManager.getConnection(url())) {
            assertEquals(3, scalar(c, "SELECT COUNT(*) FROM foo"));
        }
    }

    @Test
    void isolationLevels() throws SQLException {
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.getTransactionIsolation());
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        conn.setAutoCommit(false);
        assertEquals(1, scalar(conn, "VALUES 1"));
        conn.rollback();
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        for (int level : new int[] {Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE}) {
            SQLException e = assertThrows(SQLFeatureNotSupportedException.class,
                    () -> conn.setTransactionIsolation(level));
            assertEquals("HYC00", e.getSQLState());
        }

        // Read only is a hint.
        conn.setReadOnly(true);
        assertTrue(conn.isReadOnly());
        assertEquals(1, scalar(conn, "VALUES 1"));
    }

    @Test
    void persistence() throws SQLException {
        try (Connection c = DriverManager.getConnection(url())) {
            c.createStatement().execute("CREATE TABLE foo (x VARCHAR(10))");
            c.createStatement().execute("INSERT INTO foo (x) VALUES ('saved')");
        }
        try (Connection c = new VsqlDataSource(url()).getConnection()) {
            assertEquals("saved", scalar(c, "SELECT x FROM foo"));
        }
    }

    @Test
    void memoryDatabasesAreSeparate() throws SQLException {
        try (Connection a = DriverManager.getConnection("jdbc:vsql::memory:");
                Connection b = DriverManager.getConnection("jdbc:vsql::memory:")) {
            a.createStatement().execute("CREATE TABLE foo (x INTEGER)");
            assertThrows(SQLSyntaxErrorException.class, () -> b.createStatement().execute("SELECT * FROM foo"));
        }
    }

    @Test
    void closing() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:vsql::memory:");
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("VALUES 1");
        c.close();
        c.close();
        assertTrue(c.isClosed());
        assertFalse(c.isValid(0));
        assertTrue(s.isClosed());
        assertTrue(rs.isClosed());
        assertEquals("08003", assertThrows(SQLException.class, c::createStatement).getSQLState());
        s.close();
    }

    @Test
    void driver() throws SQLException {
        assertInstanceOf(VsqlDriver.class, DriverManager.getDriver("jdbc:vsql::memory:"));
        assertNull(new VsqlDriver().connect("jdbc:other:x", null));
        assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:vsql:"));
        assertTrue(conn.isWrapperFor(VsqlConnection.class));
        assertInstanceOf(VsqlConnection.class, conn.unwrap(Connection.class));
        assertThrows(SQLFeatureNotSupportedException.class, () -> conn.prepareCall("CALL foo()"));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
    }

    /**
     * Many connections from many threads, with enough work for the garbage
     * collector to run.
     */
    @Test
    void concurrency() throws Exception {
        try (Connection c = DriverManager.getConnection(url())) {
            c.createStatement().execute("CREATE TABLE foo (g INTEGER, x INTEGER)");
        }

        int threads = 8;
        int inserts = 50;
        List<Thread> ts = new ArrayList<>();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int g = 0; g < threads; g++) {
            int group = g;
            ts.add(Thread.ofPlatform().start(() -> {
                try (Connection c = DriverManager.getConnection(url());
                        PreparedStatement insert = c.prepareStatement("INSERT INTO foo (g, x) VALUES (?, ?)")) {
                    for (int i = 0; i < inserts; i++) {
                        insert.setInt(1, group);
                        insert.setInt(2, i);
                        insert.executeUpdate();
                        assertEquals(i + 1, scalar(c, "SELECT COUNT(*) FROM foo WHERE g = ?", group));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(List.of(), errors);

        try (Connection c = DriverManager.getConnection(url())) {
            assertEquals(threads * inserts, scalar(c, "SELECT COUNT(*) FROM foo"));
        }
    }

    /** A connection can be shared by threads, which use it one at a time. */
    @Test
    void sharedConnection() throws Exception {
        exec("CREATE TABLE foo (x INTEGER)");
        List<Thread> ts = new ArrayList<>();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 4; t++) {
            ts.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (x) VALUES (?)")) {
                            s.setInt(1, i);
                            s.executeUpdate();
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(List.of(), errors);
        assertEquals(100, scalar(conn, "SELECT COUNT(*) FROM foo"));
    }
}

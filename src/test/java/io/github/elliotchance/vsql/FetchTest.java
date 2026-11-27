package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reading rows with the buffers that a result set reuses for every row, and
 * the values that the getters parse once for each row.
 */
class FetchTest {
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

    void exec(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    static String string(int n, char c) {
        return String.valueOf(c).repeat(n);
    }

    static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i * 7 + seed);
        }
        return b;
    }

    @Test
    void longAndShortValuesInTheSameResultSet() throws SQLException {
        exec("CREATE TABLE foo (n INTEGER, s VARCHAR(10000), b VARBINARY(10000))");

        // A long value makes the buffer larger, and the shorter values after
        // it (including empty and NULL) must not see what was in it. Multi-byte
        // characters cross the pieces that a long string is read in.
        List<String> strings = new ArrayList<>();
        List<byte[]> binaries = new ArrayList<>();
        int[] lengths = {5000, 3, 0, -1, 257, 9999, 255, 256, 1, 4000, -1, 2};
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO foo (n, s, b) VALUES (?, ?, ?)")) {
            for (int i = 0; i < lengths.length; i++) {
                int n = lengths[i];
                String s = n < 0 ? null : "é".repeat(n / 2) + string(n % 2, (char) ('a' + i));
                byte[] b = n < 0 ? null : bytes(n, i);
                strings.add(s);
                binaries.add(b);
                p.setInt(1, i);
                p.setString(2, s);
                p.setBytes(3, b);
                p.executeUpdate();
            }
        }

        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT n, s, b FROM foo ORDER BY n")) {
            for (int i = 0; i < lengths.length; i++) {
                assertTrue(rs.next());
                assertEquals(i, rs.getInt(1));
                assertEquals(strings.get(i), rs.getString(2), "row " + i);
                assertArrayEquals(binaries.get(i), rs.getBytes(3), "row " + i);
            }
            assertFalse(rs.next());
        }
    }

    @Test
    void valuesLongerThanTheBufferThatsKept() throws SQLException {
        // A value over 1 MiB is read into a buffer that's only used for it.
        exec("CREATE TABLE foo (n INTEGER, b BLOB(3000000))");
        byte[][] values = {bytes(1_100_000, 1), bytes(10, 2), bytes(1_200_000, 3), bytes(300, 4)};
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO foo (n, b) VALUES (?, ?)")) {
            for (int i = 0; i < values.length; i++) {
                p.setInt(1, i);
                p.setBytes(2, values[i]);
                p.executeUpdate();
            }
        }

        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT b FROM foo ORDER BY n")) {
            for (byte[] want : values) {
                assertTrue(rs.next());
                assertArrayEquals(want, rs.getBytes(1));
            }
            assertFalse(rs.next());
        }
    }

    @Test
    void manyRowsOfMixedTypes() throws SQLException {
        exec("CREATE TABLE foo (i INTEGER, d DOUBLE PRECISION, r REAL, n NUMERIC(10, 2), s VARCHAR(20), "
                + "bo BOOLEAN, dt DATE)");
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO foo (i, d, r, n, s, bo, dt) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 500; i++) {
                boolean nulls = i % 7 == 3;
                p.setInt(1, i);
                p.setObject(2, nulls ? null : i * 1.5);
                p.setObject(3, nulls ? null : (float) i);
                p.setBigDecimal(4, nulls ? null : BigDecimal.valueOf(i, 2));
                p.setString(5, nulls ? null : "s" + i);
                p.setObject(6, nulls ? null : i % 2 == 0);
                p.setObject(7, nulls ? null : LocalDate.of(2020, 1, 1).plusDays(i));
                p.executeUpdate();
            }
        }

        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT i, d, r, n, s, bo, dt FROM foo ORDER BY i")) {
            for (int i = 0; i < 500; i++) {
                assertTrue(rs.next());
                assertEquals(i, rs.getInt(1));
                if (i % 7 == 3) {
                    assertEquals(0, rs.getDouble(2));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getObject(3));
                    assertNull(rs.getBigDecimal(4));
                    assertNull(rs.getString(5));
                    assertFalse(rs.getBoolean(6));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getObject(7, LocalDate.class));
                    continue;
                }
                assertEquals(i * 1.5, rs.getDouble(2));
                assertEquals((float) i, rs.getFloat(3));
                assertEquals(0, BigDecimal.valueOf(i, 2).compareTo(rs.getBigDecimal(4)));
                assertEquals("s" + i, rs.getString(5));
                assertEquals(i % 2 == 0, rs.getBoolean(6));
                assertEquals(LocalDate.of(2020, 1, 1).plusDays(i), rs.getObject(7, LocalDate.class));
            }
            assertFalse(rs.next());
        }
    }

    @Test
    void parsedValuesAreForTheirRow() throws SQLException {
        exec("CREATE TABLE foo (k INTEGER, n NUMERIC(10, 2), c VARCHAR(10), ts TIMESTAMP(0), "
                + "iv INTERVAL DAY(8) TO SECOND(6), ym INTERVAL YEAR(8) TO MONTH)");
        exec("INSERT INTO foo (k, n, c, ts, iv, ym) VALUES (1, 1.25, ' 7 ', TIMESTAMP '2021-02-03 04:05:06', "
                + "INTERVAL '1 02:03:04' DAY TO SECOND, INTERVAL '1-02' YEAR TO MONTH)");
        exec("INSERT INTO foo (k, n, c, ts, iv, ym) VALUES (2, 2.50, '8', TIMESTAMP '2022-03-04 05:06:07', "
                + "INTERVAL '2 03:04:05' DAY TO SECOND, INTERVAL '2-03' YEAR TO MONTH)");
        exec("INSERT INTO foo (k, n, c, ts, iv, ym) VALUES (3, NULL, NULL, NULL, NULL, NULL)");

        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT n, c, ts, iv, ym FROM foo ORDER BY k")) {
            assertTrue(rs.next());
            BigDecimal n = rs.getBigDecimal(1);
            assertEquals(0, new BigDecimal("1.25").compareTo(n));
            // The same value each time, and every getter agrees with it.
            assertSame(n, rs.getBigDecimal(1));
            assertEquals(n, rs.getObject(1));
            assertEquals(1, rs.getInt(1));
            assertEquals(1.25, rs.getDouble(1));
            // A character string is read as a number, but it's still a string.
            assertEquals(7, rs.getInt(2));
            assertEquals(0, new BigDecimal("7").compareTo(rs.getBigDecimal(2)));
            assertEquals(" 7 ", rs.getString(2));
            assertEquals(LocalDateTime.of(2021, 2, 3, 4, 5, 6), rs.getObject(3, LocalDateTime.class));
            assertEquals(LocalDate.of(2021, 2, 3), rs.getObject(3, LocalDate.class));
            assertEquals(Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4), rs.getObject(4));
            assertEquals(rs.getObject(4), rs.getObject(4, Duration.class));
            assertEquals(Period.of(1, 2, 0), rs.getObject(5));

            // A value that was parsed as one type can't be read as another.
            assertThrows(SQLException.class, () -> rs.getDate(1));
            assertThrows(SQLException.class, () -> rs.getObject(1, Duration.class));
            assertThrows(SQLException.class, () -> rs.getBigDecimal(3));
            assertThrows(SQLException.class, () -> rs.getObject(4, Period.class));
            assertThrows(SQLException.class, () -> rs.getDate(4));

            assertTrue(rs.next());
            assertEquals(0, new BigDecimal("2.5").compareTo(rs.getBigDecimal(1)));
            assertEquals(8, rs.getInt(2));
            assertEquals(LocalDateTime.of(2022, 3, 4, 5, 6, 7), rs.getObject(3, LocalDateTime.class));
            assertEquals(Duration.ofDays(2).plusHours(3).plusMinutes(4).plusSeconds(5), rs.getObject(4));
            assertEquals(Period.of(2, 3, 0), rs.getObject(5));

            assertTrue(rs.next());
            for (int i = 1; i <= 5; i++) {
                assertNull(rs.getObject(i));
                assertTrue(rs.wasNull());
            }
            assertNull(rs.getBigDecimal(1));
            assertNull(rs.getObject(3, LocalDateTime.class));

            assertFalse(rs.next());
            assertThrows(SQLException.class, () -> rs.getBigDecimal(1));
        }
    }

    @Test
    void anInvalidNumberIsStillAnError() throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("VALUES ROW('x')")) {
            assertTrue(rs.next());
            assertThrows(SQLException.class, () -> rs.getBigDecimal(1));
            assertThrows(SQLException.class, () -> rs.getInt(1));
            assertEquals("x", rs.getString(1));
        }
    }

    @Test
    void resultSetUsedByAnotherThread() throws Exception {
        // The buffers aren't confined to the thread that executed the query.
        exec("CREATE TABLE foo (n INTEGER, s VARCHAR(1000))");
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO foo (n, s) VALUES (?, ?)")) {
            for (int i = 0; i < 4; i++) {
                p.setInt(1, i);
                p.setString(2, string(300 * i, 'x'));
                p.executeUpdate();
            }
        }

        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT s FROM foo ORDER BY n")) {
            assertTrue(rs.next());
            assertEquals("", rs.getString(1));

            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            Thread t = Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 1; i < 4; i++) {
                        assertTrue(rs.next());
                        assertEquals(string(300 * i, 'x'), rs.getString(1));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            t.join();
            assertEquals(List.of(), errors);
            assertFalse(rs.next());
        }
    }
}

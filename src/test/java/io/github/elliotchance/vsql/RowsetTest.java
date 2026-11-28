package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A forward only result set reads rowsets, see Native.Fetcher. */
class RowsetTest {
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE foo (i INTEGER, b BIGINT, d DOUBLE PRECISION, r REAL, s VARCHAR(10), "
                    + "c CHAR(3), n DECIMAL(10, 2), f DECFLOAT, t BOOLEAN, dt DATE, ts TIMESTAMP(6), "
                    + "tz TIMESTAMP WITH TIME ZONE, iv INTERVAL DAY(3) TO SECOND(3), v VARBINARY(4))");
            for (int i = 1; i <= 150; i++) {
                if (i % 7 == 0) {
                    s.execute("INSERT INTO foo (i) VALUES (" + i + ")");
                    continue;
                }
                s.execute("INSERT INTO foo VALUES (" + i + ", " + (i * 1000000000L) + ", " + i + ".5, " + i
                        + ".25, '" + "é".repeat(i % 11) + "', 'x" + (i % 10) + "', " + i + ".75, " + i
                        + "E" + i + ", " + (i % 2 == 0) + ", DATE '2024-01-" + (10 + i % 18)
                        + "', TIMESTAMP '2024-02-03 04:05:06." + (100000 + i) + "', TIMESTAMP '2024-02-03 04:05:06+"
                        + String.format("%02d", i % 10) + ":00', INTERVAL '" + i + " 01:02:03.456' DAY(3) TO SECOND(3), X'"
                        + String.format("%08x", i) + "')");
            }
        }
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    /** Each row of a query as getObject and getString of every column. */
    static List<List<Object>> read(Statement s, String sql) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    Object o = rs.getObject(i);
                    row.add(o instanceof byte[] b ? HexFormat.of().formatHex(b) : o);
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * A scrollable result set reads each row with SQLGetData, so a forward
     * only one has to read the same values.
     */
    @Test
    void sameAsSqlGetData() throws SQLException {
        String sql = "SELECT * FROM foo ORDER BY i";
        List<List<Object>> expected;
        try (Statement s = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            expected = read(s, sql);
        }
        assertEquals(150, expected.size());
        for (int fetchSize : new int[] {0, 1, 2, 7, 64, 149, 150, 151, 1000}) {
            try (Statement s = conn.createStatement()) {
                s.setFetchSize(fetchSize);
                assertEquals(expected, read(s, sql), "fetch size " + fetchSize);
            }
        }
    }

    @Test
    void maxRows() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.setFetchSize(4);
            s.setMaxRows(10);
            assertEquals(10, read(s, "SELECT i FROM foo").size());
            s.setFetchSize(0);
            s.setMaxRows(3);
            assertEquals(3, read(s, "SELECT i FROM foo").size());
        }
        assertEquals(3, VsqlResultSet.rowset(0, 3));
        assertEquals(64, VsqlResultSet.rowset(0, 0));
        assertEquals(10, VsqlResultSet.rowset(10, 100));
    }

    @Test
    void rowErrors() {
        SQLException warning = new SQLException("w", "01000");
        assertEquals(warning, Native.Fetcher.rowError(warning));
        SQLException error = new SQLException("e", "22018");
        warning.setNextException(error);
        assertEquals(error, Native.Fetcher.rowError(warning));
    }

    @Test
    void widths() {
        assertEquals(8, Native.Fetcher.width(new Column("", Native.SQL_INTEGER, 0, (short) 0, true)));
        assertEquals(4, Native.Fetcher.width(new Column("", Native.SQL_REAL, 0, (short) 0, true)));
        assertEquals(11, Native.Fetcher.width(new Column("", Native.SQL_VARCHAR, 10, (short) 0, true)));
        assertEquals(10, Native.Fetcher.width(new Column("", Native.SQL_VARBINARY, 10, (short) 0, true)));
        assertEquals(0, Native.Fetcher.width(new Column("", Native.SQL_VARCHAR, 5000, (short) 0, true)));
        assertEquals(0, Native.Fetcher.width(new Column("", Native.SQL_CLOB, 10, (short) 0, true)));
        assertEquals(0, Native.Fetcher.width(new Column("", Native.SQL_BLOB, 10, (short) 0, true)));
        assertEquals(0, Native.Fetcher.width(new Column("", Native.SQL_VARCHAR, 0, (short) 0, true)));
        assertEquals(0, Native.Fetcher.width(new Column("", Native.SQL_DECIMAL, 0, (short) 0, true)));
        assertEquals(16, Native.Fetcher.width(new Column("", Native.SQL_BOOLEAN, 0, (short) 0, true)));
        assertEquals(74, Native.Fetcher.width(new Column("", Native.SQL_DECIMAL, 10, (short) 2, true)));
    }

    /** A column that can be longer than WIDTH is read with SQLGetData. */
    @Test
    void longColumns() throws SQLException {
        String text = "abcé".repeat(3000);
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE big (x INTEGER, c CLOB(1M), b BLOB(1M), v VARCHAR(20000))");
            s.execute("INSERT INTO big VALUES (1, '" + text + "', X'" + "ab".repeat(9000) + "', '" + text
                    + "'), (2, NULL, NULL, NULL)");
            for (String c : new String[] {"c", "b", "v"}) {
                try (ResultSet rs = s.executeQuery("SELECT x, " + c + " FROM big ORDER BY x")) {
                    assertTrue(rs.next());
                    if (c.equals("b")) {
                        assertEquals(9000, rs.getBytes(2).length);
                    } else {
                        assertEquals(text, rs.getString(2));
                    }
                    assertTrue(rs.next());
                    assertNull(rs.getObject(2));
                    assertFalse(rs.next());
                }
            }
        }
    }

    /**
     * The columns are unbound when the cursor is closed (at the end, or when
     * the result set is closed early), so the statement can read another
     * query with SQLGetData.
     */
    @Test
    void unbound() throws SQLException {
        try (Statement s = conn.createStatement()) {
            for (int i = 0; i < 3; i++) {
                try (ResultSet rs = s.executeQuery("SELECT i, s FROM foo ORDER BY i")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
                assertEquals(150, read(s, "SELECT i FROM foo").size());
                try (ResultSet rs = s.executeQuery("VALUES (CAST('x' AS CLOB(1M)), 1, 2)")) {
                    assertTrue(rs.next());
                    assertEquals("x", rs.getString(1));
                    assertEquals(2, rs.getInt(3));
                }
            }
        }
    }

    /**
     * The rows before a row that can't be fetched are read, and then that row
     * is an error.
     */
    @Test
    void rowError() throws SQLException {
        try (VsqlStatement s = (VsqlStatement) conn.createStatement()) {
            Column[] columns = {new Column("X", Native.SQL_INTEGER, 0, (short) 0, true)};
            Native.execDirect(s.handle(), "VALUES '1', '2', 'abc', '4', 'def'");
            Native.Fetcher fetcher = new Native.Fetcher(s.handle(), columns, 10);
            assertTrue(fetcher.rowsets());
            assertArrayEquals(new Object[] {1L}, fetcher.next());
            assertArrayEquals(new Object[] {2L}, fetcher.next());
            assertEquals("22018", assertThrows(SQLException.class, fetcher::next).getSQLState());
            fetcher.unbind();
            fetcher.unbind();
            Native.closeCursor(s.handle());

            // The first row of the rowset.
            Native.execDirect(s.handle(), "VALUES 'abc', '1'");
            Native.Fetcher first = new Native.Fetcher(s.handle(), columns, 10);
            assertEquals("22018", assertThrows(SQLException.class, first::next).getSQLState());
            first.unbind();
            Native.closeCursor(s.handle());

            // Every row of the rowset.
            Native.execDirect(s.handle(), "VALUES 'abc'");
            Native.Fetcher all = new Native.Fetcher(s.handle(), columns, 10);
            assertEquals("22018", assertThrows(SQLException.class, all::next).getSQLState());
            all.unbind();
            Native.closeCursor(s.handle());
        }
    }

    /** A positioned UPDATE with the generated cursor name, see the README. */
    @Test
    void generatedCursorName() throws SQLException {
        try (Statement s = conn.createStatement(); Statement u = conn.createStatement()) {
            s.setFetchSize(1);
            int second;
            try (ResultSet rs = s.executeQuery("SELECT i FROM foo FOR UPDATE")) {
                assertTrue(rs.next());
                assertTrue(rs.next());
                second = rs.getInt(1);
                assertEquals(1, u.executeUpdate("UPDATE foo SET i = 1000 WHERE CURRENT OF " + rs.getCursorName()));
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM foo WHERE i = " + second)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    /** The arrays of a rowset are at most KEEP octets. */
    @Test
    void largeRows() throws SQLException {
        Column[] columns = {new Column("X", Native.SQL_VARCHAR, 4000, (short) 0, true)};
        try (VsqlStatement s = (VsqlStatement) conn.createStatement()) {
            assertTrue(new Native.Fetcher(s.handle(), columns, 1000).rowsets());
            assertFalse(new Native.Fetcher(s.handle(), columns, 0).rowsets());
            Column[] clob = {new Column("X", Native.SQL_CLOB, 4000, (short) 0, true)};
            assertFalse(new Native.Fetcher(s.handle(), clob, 1000).rowsets());
            s.setFetchSize(1000);
            String text = "x".repeat(3000);
            s.execute("CREATE TABLE wide (x VARCHAR(4000))");
            for (int i = 0; i < 300; i++) {
                s.execute("INSERT INTO wide VALUES ('" + text + "')");
            }
            try (ResultSet rs = s.executeQuery("SELECT x FROM wide")) {
                int n = 0;
                while (rs.next()) {
                    assertEquals(text, rs.getString(1));
                    n++;
                }
                assertEquals(300, n);
            }
        }
    }
}

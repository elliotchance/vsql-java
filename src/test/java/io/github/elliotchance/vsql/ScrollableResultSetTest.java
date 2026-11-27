package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ScrollableResultSetTest {
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

    /** A table foo with the rows 1 to n. */
    void insertNumbers(int n) throws SQLException {
        exec("CREATE TABLE foo (x INTEGER)");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (x) VALUES (?)")) {
            for (int i = 1; i <= n; i++) {
                s.setInt(1, i);
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    Statement scrollable() throws SQLException {
        return conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    }

    /** Asserts the row number, which is also x (0 is no row, which has no x). */
    static void assertRow(ResultSet rs, int row) throws SQLException {
        assertEquals(row, rs.getRow());
        if (row > 0) {
            assertEquals(row, rs.getInt(1));
        } else {
            assertEquals("24000", assertThrows(SQLException.class, () -> rs.getInt(1)).getSQLState());
        }
    }

    @Test
    void scroll() throws SQLException {
        insertNumbers(5);
        try (Statement s = scrollable(); ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
            assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, s.getResultSetType());
            assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());
            assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());

            assertTrue(rs.isBeforeFirst());
            assertFalse(rs.isAfterLast());
            assertRow(rs, 0);
            assertTrue(rs.next());
            assertRow(rs, 1);
            assertTrue(rs.isFirst());
            assertFalse(rs.isLast());
            assertFalse(rs.isBeforeFirst());

            assertTrue(rs.last());
            assertRow(rs, 5);
            assertTrue(rs.isLast());
            assertFalse(rs.isFirst());
            assertTrue(rs.previous());
            assertRow(rs, 4);
            assertTrue(rs.first());
            assertRow(rs, 1);
            assertTrue(rs.absolute(3));
            assertRow(rs, 3);
            assertTrue(rs.absolute(-2));
            assertRow(rs, 4);
            assertTrue(rs.relative(-2));
            assertRow(rs, 2);
            assertTrue(rs.relative(2));
            assertRow(rs, 4);
            assertTrue(rs.relative(0));
            assertRow(rs, 4);
            assertTrue(rs.absolute(-5));
            assertRow(rs, 1);

            // 0 is before the first row.
            assertFalse(rs.absolute(0));
            assertRow(rs, 0);
            assertTrue(rs.isBeforeFirst());
            assertFalse(rs.previous());
            assertTrue(rs.isBeforeFirst());
            assertTrue(rs.relative(1));
            assertRow(rs, 1);

            // After the last row.
            assertFalse(rs.absolute(6));
            assertRow(rs, 0);
            assertTrue(rs.isAfterLast());
            assertFalse(rs.isLast());
            assertTrue(rs.previous());
            assertRow(rs, 5);
            assertFalse(rs.next());
            assertTrue(rs.isAfterLast());
            assertFalse(rs.next());
            assertTrue(rs.isAfterLast());
            assertTrue(rs.relative(-1));
            assertRow(rs, 5);
            assertFalse(rs.relative(1));
            assertTrue(rs.isAfterLast());
            assertTrue(rs.relative(-5));
            assertRow(rs, 1);

            // Before the first row.
            assertFalse(rs.absolute(-6));
            assertTrue(rs.isBeforeFirst());
            assertTrue(rs.absolute(2));
            assertFalse(rs.relative(-10));
            assertTrue(rs.isBeforeFirst());
            assertTrue(rs.next());
            assertRow(rs, 1);

            rs.afterLast();
            assertTrue(rs.isAfterLast());
            assertRow(rs, 0);
            assertTrue(rs.previous());
            assertRow(rs, 5);
            rs.beforeFirst();
            assertTrue(rs.isBeforeFirst());
            assertRow(rs, 0);
            assertTrue(rs.next());
            assertRow(rs, 1);
        }
    }

    @Test
    void forwardAndBack() throws SQLException {
        insertNumbers(3);
        try (Statement s = scrollable(); ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
            List<Integer> xs = new ArrayList<>();
            while (rs.next()) {
                xs.add(rs.getInt(1));
            }
            // The cursor is still open after the last row.
            assertFalse(rs.isClosed());
            while (rs.previous()) {
                xs.add(rs.getInt(1));
            }
            assertEquals(List.of(1, 2, 3, 3, 2, 1), xs);
            assertTrue(rs.isBeforeFirst());
        }
    }

    @Test
    void rowCounts() throws SQLException {
        insertNumbers(100);
        for (int n : new int[] {1, 2, 3, 63, 64, 65, 100}) {
            try (Statement s = scrollable();
                    ResultSet rs = s.executeQuery("SELECT x FROM foo WHERE x <= " + n + " ORDER BY x")) {
                assertTrue(rs.last());
                assertRow(rs, n);
                assertTrue(rs.isLast());
                assertTrue(rs.absolute(-1));
                assertRow(rs, n);
                assertTrue(rs.absolute(1));
                assertRow(rs, 1);
                assertEquals(n == 1, rs.isLast());
            }
        }
    }

    @Test
    void emptyResultSet() throws SQLException {
        exec("CREATE TABLE foo (x INTEGER)");
        try (Statement s = scrollable(); ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.isAfterLast());
            assertFalse(rs.first());
            assertFalse(rs.last());
            assertFalse(rs.absolute(1));
            assertFalse(rs.absolute(-1));
            assertFalse(rs.isAfterLast());
            rs.afterLast();
            assertFalse(rs.isAfterLast());
            assertFalse(rs.next());
            assertFalse(rs.isAfterLast());
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.previous());
            assertFalse(rs.isFirst());
            assertFalse(rs.isLast());
            assertRow(rs, 0);
        }
    }

    @Test
    void maxRows() throws SQLException {
        insertNumbers(5);
        try (Statement s = scrollable()) {
            s.setMaxRows(3);
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
                assertTrue(rs.last());
                assertRow(rs, 3);
                assertTrue(rs.isLast());
                assertFalse(rs.absolute(4));
                assertTrue(rs.isAfterLast());
                assertTrue(rs.absolute(-1));
                assertRow(rs, 3);
                assertFalse(rs.next());
                assertTrue(rs.isAfterLast());
                assertTrue(rs.previous());
                assertRow(rs, 3);
            }

            // Moving forward stops at max rows, without counting the rows.
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                assertEquals(3, n);
                assertTrue(rs.isAfterLast());
            }
        }
    }

    @Test
    void preparedStatement() throws SQLException {
        insertNumbers(5);
        try (PreparedStatement s = conn.prepareStatement("SELECT x FROM foo WHERE x > ? ORDER BY x",
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, s.getResultSetType());
            s.setInt(1, 2);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.last());
                assertEquals(3, rs.getRow());
                assertEquals(5, rs.getInt(1));
                assertTrue(rs.first());
                assertEquals(3, rs.getInt(1));
            }

            // Executing again is also scrollable.
            s.setInt(1, 3);
            try (ResultSet rs = s.executeQuery()) {
                assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, rs.getType());
                assertTrue(rs.last());
                assertEquals(2, rs.getRow());
                assertTrue(rs.previous());
                assertEquals(4, rs.getInt(1));
            }
        }
    }

    @Test
    void insensitive() throws SQLException {
        insertNumbers(3);
        conn.setAutoCommit(false);
        try (Statement s = scrollable(); ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
            assertTrue(rs.next());
            exec("INSERT INTO foo (x) VALUES (4)");
            exec("UPDATE foo SET x = 10 WHERE x = 2");
            assertTrue(rs.last());
            assertEquals(3, rs.getRow());
            assertEquals(3, rs.getInt(1));
            assertTrue(rs.previous());
            assertEquals(2, rs.getInt(1));
        }
        conn.rollback();
    }

    @Test
    void fetchDirection() throws SQLException {
        insertNumbers(1);
        try (Statement s = scrollable()) {
            assertEquals(ResultSet.FETCH_FORWARD, s.getFetchDirection());
            s.setFetchDirection(ResultSet.FETCH_REVERSE);
            assertEquals(ResultSet.FETCH_REVERSE, s.getFetchDirection());
            assertEquals("HY024", assertThrows(SQLException.class, () -> s.setFetchDirection(12345)).getSQLState());
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
                assertEquals(ResultSet.FETCH_REVERSE, rs.getFetchDirection());
                rs.setFetchDirection(ResultSet.FETCH_UNKNOWN);
                assertEquals(ResultSet.FETCH_UNKNOWN, rs.getFetchDirection());
                assertEquals("HY024",
                        assertThrows(SQLException.class, () -> rs.setFetchDirection(12345)).getSQLState());
            }
        }

        // Only FETCH_FORWARD for a forward only result set.
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> s.setFetchDirection(ResultSet.FETCH_REVERSE));
            assertThrows(SQLFeatureNotSupportedException.class, () -> rs.setFetchDirection(ResultSet.FETCH_REVERSE));
            rs.setFetchDirection(ResultSet.FETCH_FORWARD);
            assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
        }
    }

    @Test
    void forwardOnly() throws SQLException {
        insertNumbers(3);
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, s.getResultSetType());
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
            assertTrue(rs.next());
            for (Executable e : new Executable[] {rs::previous, rs::first, rs::last, rs::beforeFirst, rs::afterLast,
                    () -> rs.absolute(1), () -> rs.relative(1)}) {
                assertEquals("HY106", assertThrows(SQLException.class, e).getSQLState());
            }
            assertThrows(SQLFeatureNotSupportedException.class, rs::isLast);

            // The cursor is still where it was.
            assertEquals(1, rs.getRow());
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void metadata() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        assertTrue(md.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue(md.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));
        assertFalse(md.supportsResultSetType(ResultSet.TYPE_SCROLL_SENSITIVE));
        assertTrue(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertTrue(md.supportsResultSetConcurrency(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertFalse(md.supportsResultSetConcurrency(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE));
        assertFalse(md.supportsResultSetConcurrency(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY));
    }

    @Test
    void unsupportedTypes() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.prepareStatement("VALUES 1", ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE));
    }

    @Test
    void closed() throws SQLException {
        insertNumbers(1);
        Statement s = scrollable();
        ResultSet rs = s.executeQuery("SELECT x FROM foo");
        rs.close();
        assertThrows(SQLException.class, rs::previous);
        assertThrows(SQLException.class, rs::last);
        assertThrows(SQLException.class, rs::isLast);
        assertThrows(SQLException.class, () -> rs.absolute(1));
        s.close();
    }
}

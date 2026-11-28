package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The warnings (SQL_SUCCESS_WITH_INFO) of getWarnings. */
class WarningsTest {
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

    /** The SQLSTATE of each warning in the chain. */
    static List<String> sqlstates(SQLWarning w) {
        List<String> states = new ArrayList<>();
        for (; w != null; w = w.getNextWarning()) {
            states.add(w.getSQLState());
        }
        return states;
    }

    void tableWithNull() throws SQLException {
        exec("CREATE TABLE t (x INTEGER)");
        exec("INSERT INTO t (x) VALUES (1)");
        exec("INSERT INTO t (x) VALUES (NULL)");
    }

    @Test
    void nullValueEliminated() throws SQLException {
        tableWithNull();
        try (Statement s = conn.createStatement()) {
            assertNull(s.getWarnings());
            try (ResultSet rs = s.executeQuery("SELECT SUM(x) FROM t")) {
                SQLWarning w = s.getWarnings();
                assertNotNull(w);
                assertEquals("01003", w.getSQLState());
                assertTrue(w.getMessage().contains("null value eliminated"), w.getMessage());
                assertNull(w.getNextWarning());

                // It's a VsqlException, like an error.
                VsqlException v = assertInstanceOf(VsqlException.class, w);
                assertNotNull(v.getDiagnostics());

                // The warning is the statement's, not the result set's.
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertNull(rs.getWarnings());
            }

            // The warnings stay until they're cleared.
            assertEquals(List.of("01003"), sqlstates(s.getWarnings()));
            s.clearWarnings();
            assertNull(s.getWarnings());
        }
    }

    @Test
    void executingAgainClearsWarnings() throws SQLException {
        tableWithNull();
        try (Statement s = conn.createStatement()) {
            s.executeQuery("SELECT SUM(x) FROM t").close();
            assertEquals(List.of("01003"), sqlstates(s.getWarnings()));

            s.executeQuery("SELECT x FROM t").close();
            assertNull(s.getWarnings());

            // Each execution has its own warnings, not the ones before it.
            s.executeQuery("SELECT SUM(x) FROM t").close();
            s.executeQuery("SELECT SUM(x) FROM t").close();
            assertEquals(List.of("01003"), sqlstates(s.getWarnings()));
        }
    }

    @Test
    void preparedStatementWarnings() throws SQLException {
        tableWithNull();
        try (PreparedStatement s = conn.prepareStatement("SELECT SUM(x) FROM t WHERE x IS NULL OR x > ?")) {
            assertNull(s.getWarnings());

            s.setInt(1, 0);
            s.executeQuery().close();
            assertEquals(List.of("01003"), sqlstates(s.getWarnings()));

            s.clearWarnings();
            assertNull(s.getWarnings());

            // Executing it again raises it again.
            s.executeQuery().close();
            assertEquals(List.of("01003"), sqlstates(s.getWarnings()));

            // Without the NULL there's no warning, and the one before is gone.
            exec("DELETE FROM t WHERE x IS NULL");
            s.executeQuery().close();
            assertNull(s.getWarnings());
        }
    }

    @Test
    void rightTruncation() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES ROW(CAST('abc' AS VARCHAR(1)))")) {
            SQLWarning w = s.getWarnings();
            assertNotNull(w);
            assertEquals("01004", w.getSQLState());
            assertTrue(w.getMessage().contains("right truncation"), w.getMessage());
            assertTrue(rs.next());
            assertEquals("a", rs.getString(1));
        }
    }

    @Test
    void chainedWarnings() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement(
                "VALUES ROW(CAST('abc' AS VARCHAR(1)), CAST('xyz' AS VARCHAR(1)))")) {
            try (ResultSet rs = s.executeQuery()) {
                assertEquals(List.of("01004", "01004"), sqlstates(s.getWarnings()));
                assertTrue(rs.next());
                assertEquals("a", rs.getString(1));
                assertEquals("x", rs.getString(2));
            }
        }
    }

    @Test
    void updateWarnings() throws SQLException {
        exec("CREATE TABLE s (v VARCHAR(10))");
        try (Statement s = conn.createStatement()) {
            assertEquals(1, s.executeUpdate("INSERT INTO s (v) VALUES (CAST('abc' AS VARCHAR(1)))"));
            assertEquals(List.of("01004"), sqlstates(s.getWarnings()));

            assertEquals(1, s.executeUpdate("INSERT INTO s (v) VALUES ('abc')"));
            assertNull(s.getWarnings());
        }
    }

    @Test
    void batchKeepsTheWarningsOfEveryStatement() throws SQLException {
        exec("CREATE TABLE s (v VARCHAR(10))");
        try (Statement s = conn.createStatement()) {
            s.addBatch("INSERT INTO s (v) VALUES (CAST('abc' AS VARCHAR(1)))");
            s.addBatch("INSERT INTO s (v) VALUES ('abc')");
            s.addBatch("INSERT INTO s (v) VALUES (CAST('xyz' AS VARCHAR(1)))");
            assertArrayEquals(new int[] {1, 1, 1}, s.executeBatch());
            assertEquals(List.of("01004", "01004"), sqlstates(s.getWarnings()));

            // The next batch clears them.
            s.addBatch("INSERT INTO s (v) VALUES ('abc')");
            assertArrayEquals(new int[] {1}, s.executeBatch());
            assertNull(s.getWarnings());
        }

        try (PreparedStatement p = conn.prepareStatement("INSERT INTO s (v) VALUES (CAST('abc' AS VARCHAR(1)))")) {
            p.addBatch();
            p.addBatch();
            assertArrayEquals(new int[] {1, 1}, p.executeBatch());
            assertEquals(List.of("01004", "01004"), sqlstates(p.getWarnings()));
        }
    }

    @Test
    void longValuesAreNotWarnings() throws SQLException {
        // A value that's longer than the buffer is read in pieces, each of
        // which is a right truncation for libvsql, but not a warning for the
        // application.
        exec("CREATE TABLE big (s VARCHAR(10000), b VARBINARY(10000))");
        String str = "é".repeat(1000) + "x".repeat(1001);
        byte[] bytes = new byte[3000];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        try (PreparedStatement p = conn.prepareStatement("INSERT INTO big (s, b) VALUES (?, ?)")) {
            p.setString(1, str);
            p.setBytes(2, bytes);
            p.executeUpdate();
            assertNull(p.getWarnings());
        }

        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT s, b FROM big")) {
            assertNull(s.getWarnings());
            assertTrue(rs.next());
            assertNull(rs.getWarnings());
            assertEquals(str, rs.getString(1));
            assertArrayEquals(bytes, rs.getBytes(2));
            assertNull(rs.getWarnings());
            assertFalse(rs.next());
            assertNull(rs.getWarnings());
        }
    }

    @Test
    void connectionWarnings() throws SQLException {
        // Connecting and transactions don't raise warnings here, but they can
        // be read and cleared.
        assertNull(conn.getWarnings());
        exec("CREATE TABLE t (x INTEGER)");
        conn.setAutoCommit(false);
        exec("INSERT INTO t (x) VALUES (1)");
        conn.commit();
        assertNull(conn.getWarnings());
        conn.clearWarnings();
        assertNull(conn.getWarnings());
    }

    @Test
    void closedObjects() throws SQLException {
        tableWithNull();
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT SUM(x) FROM t");
        rs.close();
        assertThrows(SQLException.class, rs::getWarnings);
        assertThrows(SQLException.class, rs::clearWarnings);
        s.close();
        assertThrows(SQLException.class, s::getWarnings);
        assertThrows(SQLException.class, s::clearWarnings);

        Connection c = DriverManager.getConnection("jdbc:vsql::memory:");
        c.close();
        assertThrows(SQLException.class, c::getWarnings);
        assertThrows(SQLException.class, c::clearWarnings);
    }
}

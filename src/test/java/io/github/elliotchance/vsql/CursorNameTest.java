package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Statement.setCursorName, ResultSet.getCursorName and positioned statements. */
class CursorNameTest {
    @TempDir
    Path tmp;

    /** A connection to a new in-memory database. */
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
        exec(conn, "CREATE TABLE foo (x INT)");
        exec(conn, "INSERT INTO foo (x) VALUES (1), (2), (3)");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static List<Integer> values(Connection c) throws SQLException {
        List<Integer> values = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
            while (rs.next()) {
                values.add(rs.getInt(1));
            }
        }
        return values;
    }

    static void assertState(String sqlstate, SQLException e) {
        assertEquals(sqlstate, e.getSQLState(), e.getMessage());
    }

    @Test
    void positionedUpdate() throws SQLException {
        assertTrue(conn.getMetaData().supportsPositionedUpdate());
        try (Statement s = conn.createStatement(); Statement u = conn.createStatement()) {
            s.setCursorName("c");
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo FOR UPDATE")) {
                assertEquals("c", rs.getCursorName());
                int n = 0;
                while (rs.next()) {
                    assertEquals(1, u.executeUpdate("UPDATE foo SET x = x + 10 WHERE CURRENT OF c"));
                    n++;
                }
                assertEquals(3, n);
            }
        }
        assertEquals(List.of(11, 12, 13), values(conn));
    }

    @Test
    void positionedDelete() throws SQLException {
        assertTrue(conn.getMetaData().supportsPositionedDelete());
        try (Statement s = conn.createStatement(); Statement u = conn.createStatement()) {
            s.setCursorName("c");
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
                while (rs.next()) {
                    if (rs.getInt(1) == 2) {
                        assertEquals(1, u.executeUpdate("DELETE FROM foo WHERE CURRENT OF C"));
                    }
                }
            }
        }
        assertEquals(List.of(1, 3), values(conn));
    }

    /**
     * With auto-commit on, the changes are committed when the result set is
     * closed, so another connection sees them.
     */
    @Test
    void autoCommit() throws SQLException {
        String url = "jdbc:vsql:" + tmp.resolve("test.vsql");
        try (Connection c = DriverManager.getConnection(url); Connection other = DriverManager.getConnection(url)) {
            exec(c, "CREATE TABLE foo (x INT)");
            exec(c, "INSERT INTO foo (x) VALUES (1), (2)");
            try (Statement s = c.createStatement(); Statement u = c.createStatement()) {
                s.setCursorName("c");
                ResultSet rs = s.executeQuery("SELECT x FROM foo");
                assertTrue(rs.next());
                assertEquals(1, u.executeUpdate("UPDATE foo SET x = 5 WHERE CURRENT OF c"));
                rs.close();
            }
            assertEquals(List.of(2, 5), values(other));
        }
    }

    /** With auto-commit off, the changes are part of the transaction. */
    @Test
    void rollback() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement(); Statement u = conn.createStatement()) {
            s.setCursorName("c");
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
                assertTrue(rs.next());
                assertEquals(1, u.executeUpdate("DELETE FROM foo WHERE CURRENT OF c"));
            }
            assertEquals(List.of(2, 3), values(conn));
        }
        conn.rollback();
        assertEquals(List.of(1, 2, 3), values(conn));
    }

    /**
     * A positioned statement can be prepared while the cursor is open, and the
     * cursor can be of a prepared statement.
     */
    @Test
    void preparedStatements() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("SELECT x FROM foo WHERE x > ?")) {
            s.setCursorName("p");
            s.setInt(1, 1);
            try (ResultSet rs = s.executeQuery()) {
                assertEquals("p", rs.getCursorName());
                assertTrue(rs.next());
                try (PreparedStatement u = conn.prepareStatement("UPDATE foo SET x = ? WHERE CURRENT OF p")) {
                    u.setInt(1, 20);
                    assertEquals(1, u.executeUpdate());
                    assertTrue(rs.next());
                    u.setInt(1, 30);
                    assertEquals(1, u.executeUpdate());
                }
            }
        }
        assertEquals(List.of(1, 20, 30), values(conn));
    }

    @Test
    void cursorNames() throws SQLException {
        try (Statement s = conn.createStatement()) {
            // Without a name, it's a unique one.
            String name;
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
                name = rs.getCursorName();
                assertTrue(name.startsWith("SQL_CUR"), name);
            }
            try (Statement s2 = conn.createStatement(); ResultSet rs = s2.executeQuery("SELECT x FROM foo")) {
                assertFalse(rs.getCursorName().equals(name));
            }

            // The name is kept as it's written, for the next result sets.
            s.setCursorName("\"lower\"");
            for (int i = 0; i < 2; i++) {
                try (ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
                    assertEquals("\"lower\"", rs.getCursorName());
                    assertTrue(rs.next());
                }
            }

            // The name can be changed.
            s.setCursorName("other");
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
                assertEquals("other", rs.getCursorName());
            }
        }
    }

    @Test
    void errors() throws SQLException {
        try (Statement s = conn.createStatement(); Statement s2 = conn.createStatement();
                Statement u = conn.createStatement()) {
            s.setCursorName("c");

            // Two statements can't have the same name, and a name can't
            // start with SQL_CUR.
            assertState("34000", assertThrows(SQLException.class, () -> s2.setCursorName("C")));
            assertState("34000", assertThrows(SQLException.class, () -> s2.setCursorName("SQL_CURX")));
            assertThrows(SQLException.class, () -> s2.setCursorName(""));
            assertThrows(SQLException.class, () -> s2.setCursorName(null));

            try (ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
                assertTrue(rs.next());

                // The name can't be changed while the result set is open.
                assertState("24000", assertThrows(SQLException.class, () -> s.setCursorName("d")));

                // A cursor that doesn't exist.
                assertState("34000", assertThrows(SQLException.class,
                        () -> u.executeUpdate("DELETE FROM foo WHERE CURRENT OF nope")));
            }

            // The cursor is closed.
            assertThrows(SQLException.class, () -> u.executeUpdate("DELETE FROM foo WHERE CURRENT OF c"));
        }
        assertEquals(List.of(1, 2, 3), values(conn));

        // A closed statement or result set.
        Statement closed = conn.createStatement();
        ResultSet rs = closed.executeQuery("SELECT x FROM foo");
        rs.close();
        assertThrows(SQLException.class, rs::getCursorName);
        closed.close();
        assertThrows(SQLException.class, () -> closed.setCursorName("c"));

        // A DatabaseMetaData result set doesn't have a cursor.
        try (ResultSet types = conn.getMetaData().getTypeInfo()) {
            assertState("24000", assertThrows(SQLException.class, types::getCursorName));
        }
    }
}

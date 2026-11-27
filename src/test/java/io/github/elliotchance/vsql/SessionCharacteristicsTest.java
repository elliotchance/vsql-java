package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCharacteristicsTest {
    @TempDir
    Path tmp;

    /** A connection to a new in-memory database. */
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
        exec(conn, "CREATE TABLE foo (x INTEGER)");
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

    static Object scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getObject(1);
        }
    }

    static String insertError(Connection c) {
        return assertThrows(SQLException.class, () -> exec(c, "INSERT INTO foo (x) VALUES (1)")).getSQLState();
    }

    @Test
    void readOnlyWithAutoCommit() throws SQLException {
        conn.setReadOnly(true);
        assertEquals("25006", insertError(conn));
        assertEquals("25006",
                assertThrows(SQLException.class, () -> exec(conn, "CREATE TABLE bar (x INTEGER)")).getSQLState());

        // Reading is fine.
        assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM foo"));

        conn.setReadOnly(false);
        exec(conn, "INSERT INTO foo (x) VALUES (1)");
        assertEquals(1, scalar(conn, "SELECT COUNT(*) FROM foo"));
    }

    @Test
    void readOnlyWithAutoCommitOff() throws SQLException {
        conn.setReadOnly(true);
        conn.setAutoCommit(false);
        assertEquals("25006", insertError(conn));
        conn.rollback();

        // Back to auto-commit, it's still read only.
        conn.setAutoCommit(true);
        assertEquals("25006", insertError(conn));
    }

    @Test
    void readUncommittedWithAutoCommit() throws SQLException {
        // A READ UNCOMMITTED transaction is READ ONLY, but the isolation level
        // doesn't apply to auto-commit statements.
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
        exec(conn, "INSERT INTO foo (x) VALUES (1)");

        // Read only does, whatever the level.
        conn.setReadOnly(true);
        for (int level : new int[] {Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE}) {
            conn.setTransactionIsolation(level);
            assertEquals("25006", insertError(conn));
        }
        assertEquals(1, scalar(conn, "SELECT COUNT(*) FROM foo"));
    }

    @Test
    void eachLevelWithAutoCommit() throws SQLException {
        for (int level : new int[] {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ,
                Connection.TRANSACTION_SERIALIZABLE}) {
            conn.setTransactionIsolation(level);
            exec(conn, "INSERT INTO foo (x) VALUES (1)");
        }
        assertEquals(3, scalar(conn, "SELECT COUNT(*) FROM foo"));
    }

    @Test
    void changedWithResultSetOpen() throws SQLException {
        exec(conn, "INSERT INTO foo (x) VALUES (1)");
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
            // The statement's transaction is still active, which it doesn't
            // change.
            conn.setReadOnly(true);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        assertEquals("25006", insertError(conn));
    }

    @Test
    void onlyThisConnection() throws SQLException {
        String url = "jdbc:vsql:" + tmp.resolve("test.vsql");
        try (Connection a = DriverManager.getConnection(url); Connection b = DriverManager.getConnection(url)) {
            exec(a, "CREATE TABLE foo (x INTEGER)");
            a.setReadOnly(true);
            assertEquals("25006", insertError(a));
            exec(b, "INSERT INTO foo (x) VALUES (1)");
            assertEquals(1, scalar(a, "SELECT COUNT(*) FROM foo"));
        }
    }

    @Test
    void notInTransaction() throws SQLException {
        conn.setAutoCommit(false);
        exec(conn, "INSERT INTO foo (x) VALUES (1)");
        assertEquals("25001", assertThrows(SQLException.class, () -> conn.setReadOnly(true)).getSQLState());
        assertEquals("25001", assertThrows(SQLException.class,
                () -> conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)).getSQLState());
        conn.commit();

        // Neither changed, so auto-commit statements can still change data.
        conn.setAutoCommit(true);
        exec(conn, "INSERT INTO foo (x) VALUES (2)");
        assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM foo"));
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.getTransactionIsolation());
    }
}

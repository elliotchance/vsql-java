package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The driver's transaction (with auto-commit off) must end when vsql ends it,
 * so that the next statement starts a new one instead of being committed by
 * itself.
 */
class TransactionStateTest {
    @TempDir
    Path tmp;

    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE foo (x INTEGER)");
        }
        conn.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** libvsql only ends a transaction with SQLEndTran. */
    @Test
    void sqlCommitIsAnError() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("INSERT INTO foo (x) VALUES (1)");
            assertEquals("42000", assertThrows(SQLException.class, () -> s.execute("COMMIT")).getSQLState());
            s.execute("INSERT INTO foo (x) VALUES (2)");
        }
        conn.rollback();
        assertEquals(0, count(conn, "foo"));
    }

    /**
     * A serialization failure rolls back the transaction, so the statements
     * after it are in a new one.
     */
    @Test
    void serializationFailure() throws SQLException {
        String url = "jdbc:vsql:" + tmp.resolve("test.vsql");
        try (Connection c1 = DriverManager.getConnection(url); Connection c2 = DriverManager.getConnection(url)) {
            try (Statement s = c1.createStatement()) {
                s.execute("CREATE TABLE r (x INTEGER)");
                s.execute("CREATE TABLE t (x INTEGER)");
                s.execute("INSERT INTO r (x) VALUES (1)");
            }

            c1.setAutoCommit(false);
            c2.setAutoCommit(false);
            try (Statement s1 = c1.createStatement(); Statement s2 = c2.createStatement()) {
                s1.execute("UPDATE r SET x = 2");
                s2.execute("INSERT INTO t (x) VALUES (1)");
                SQLException e = assertThrows(SQLException.class, () -> s2.execute("UPDATE r SET x = 3"));
                assertEquals("40001", e.getSQLState());
                assertInstanceOf(SQLTransactionRollbackException.class, e);

                s2.execute("INSERT INTO t (x) VALUES (2)");
                c2.rollback();
                c1.rollback();
            }

            c2.setAutoCommit(true);
            assertEquals(0, count(c2, "t"));
        }
    }
}

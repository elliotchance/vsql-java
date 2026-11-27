package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Statement.closeOnCompletion (#4). */
class CloseOnCompletionTest {
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

    static int first(ResultSet rs) throws SQLException {
        assertTrue(rs.next());
        return rs.getInt(1);
    }

    /** Executing the statement again doesn't close it. */
    @Test
    void executeAgain() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.closeOnCompletion();
            ResultSet rs1 = s.executeQuery("VALUES 1");
            ResultSet rs2 = s.executeQuery("VALUES 2");
            assertTrue(rs1.isClosed());
            assertFalse(s.isClosed());
            assertEquals(2, first(rs2));
            assertFalse(s.execute("CREATE TABLE foo (x INTEGER)"));
            assertTrue(rs2.isClosed());
            assertFalse(s.isClosed());

            // It's closed when the last result set is closed.
            ResultSet rs3 = s.executeQuery("VALUES 3");
            assertEquals(3, first(rs3));
            assertFalse(s.isClosed());
            rs3.close();
            assertTrue(s.isClosed());
            assertThrows(SQLException.class, () -> s.executeQuery("VALUES 4"));
        }
    }

    /** Executing a prepared statement again doesn't close it. */
    @Test
    void preparedExecuteAgain() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("VALUES CAST(? AS INTEGER)")) {
            ps.closeOnCompletion();
            ps.setInt(1, 1);
            ResultSet rs1 = ps.executeQuery();
            ps.setInt(1, 2);
            ResultSet rs2 = ps.executeQuery();
            assertTrue(rs1.isClosed());
            assertFalse(ps.isClosed());
            assertEquals(2, first(rs2));
            rs2.close();
            assertTrue(ps.isClosed());
        }
    }

    /** getMoreResults closes the result set, but not the statement. */
    @Test
    void getMoreResults() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.closeOnCompletion();
            assertTrue(s.execute("VALUES 1"));
            ResultSet rs = s.getResultSet();
            assertFalse(s.getMoreResults());
            assertTrue(rs.isClosed());
            assertFalse(s.isClosed());
            assertEquals(1, first(s.executeQuery("VALUES 1")));
        }
    }

    /** A query that's executed as an update doesn't close the statement. */
    @Test
    void executeUpdateQuery() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.closeOnCompletion();
            assertThrows(SQLException.class, () -> s.executeUpdate("VALUES 1"));
            assertFalse(s.isClosed());
            assertEquals(1, first(s.executeQuery("VALUES 1")));
        }
    }

    /** Reading all the rows doesn't close it, closing the result set does. */
    @Test
    void closedByUser() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.closeOnCompletion();
            ResultSet rs = s.executeQuery("VALUES 1");
            assertEquals(1, first(rs));
            assertFalse(rs.next());
            assertFalse(s.isClosed());
            rs.close();
            assertTrue(s.isClosed());
        }
    }

    /** The end of the transaction closes the result set and the statement. */
    @Test
    void commit() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.closeOnCompletion();
            ResultSet rs = s.executeQuery("VALUES 1");
            conn.commit();
            assertTrue(rs.isClosed());
            assertTrue(s.isClosed());
        }
    }

    @Test
    void rollback() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.closeOnCompletion();
            ResultSet rs = s.executeQuery("VALUES 1");
            conn.rollback();
            assertTrue(rs.isClosed());
            assertTrue(s.isClosed());
        }
    }

    /** Without closeOnCompletion, nothing closes the statement. */
    @Test
    void notCloseOnCompletion() throws SQLException {
        try (Statement s = conn.createStatement()) {
            assertFalse(s.isCloseOnCompletion());
            s.executeQuery("VALUES 1").close();
            assertFalse(s.isClosed());
            s.closeOnCompletion();
            assertTrue(s.isCloseOnCompletion());
        }
    }
}

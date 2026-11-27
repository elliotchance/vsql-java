package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The options of a Statement. */
class StatementTest {
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    static void assertSqlState(String sqlstate, Executable e) {
        assertEquals(sqlstate, assertThrows(SQLException.class, e).getSQLState());
    }

    @Test
    void generatedKeys() throws SQLException {
        try (Statement s = conn.createStatement()) {
            assertTrue(s.execute("VALUES 1", Statement.NO_GENERATED_KEYS));
            s.executeUpdate("CREATE TABLE foo (x INTEGER)", Statement.NO_GENERATED_KEYS);
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> s.execute("VALUES 1", Statement.RETURN_GENERATED_KEYS));
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> s.executeUpdate("VALUES 1", Statement.RETURN_GENERATED_KEYS));
        }
    }

    @Test
    void options() throws SQLException {
        try (Statement s = conn.createStatement()) {
            assertSame(conn, s.getConnection());

            s.addBatch("VALUES 1");
            s.clearBatch();
            assertEquals(0, s.executeBatch().length);

            s.setMaxRows(2);
            assertEquals(2, s.getMaxRows());
            s.setLargeMaxRows(Long.MAX_VALUE);
            assertEquals(Integer.MAX_VALUE, s.getMaxRows());
            assertSqlState("HY024", () -> s.setLargeMaxRows(-1));

            s.setFetchSize(10);
            assertEquals(10, s.getFetchSize());
            assertSqlState("HY024", () -> s.setFetchSize(-1));

            s.setFetchDirection(ResultSet.FETCH_FORWARD);
            assertEquals(ResultSet.FETCH_FORWARD, s.getFetchDirection());
            assertThrows(SQLFeatureNotSupportedException.class, () -> s.setFetchDirection(ResultSet.FETCH_REVERSE));

            s.setQueryTimeout(5);
            assertEquals(5, s.getQueryTimeout());
            assertSqlState("HY024", () -> s.setQueryTimeout(-1));

            s.setMaxFieldSize(100);
            assertEquals(100, s.getMaxFieldSize());
            assertSqlState("HY024", () -> s.setMaxFieldSize(-1));

            s.setEscapeProcessing(false);
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, s.getResultSetType());
            assertEquals(ResultSet.CONCUR_READ_ONLY, s.getResultSetConcurrency());
            assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, s.getResultSetHoldability());

            assertFalse(s.isPoolable());
            s.setPoolable(true);
            assertTrue(s.isPoolable());

            assertNull(s.getWarnings());
            s.clearWarnings();

            DriverTest.assertWrapper(s, VsqlStatement.class);
        }
    }

    /** A statement that was never executed has no handle. */
    @Test
    void closeWithoutHandle() throws SQLException {
        Statement s = conn.createStatement();
        s.close();
        assertTrue(s.isClosed());

        Statement executed = conn.createStatement();
        executed.execute("VALUES 1");
        Statement unused = conn.createStatement();
        conn.close();
        assertTrue(executed.isClosed());
        assertTrue(unused.isClosed());
        executed.close();
    }
}

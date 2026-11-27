package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** The options of a Connection, and its errors. */
class ConnectionTest {
    @TempDir
    Path tmp;

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
    void options() throws SQLException {
        assertEquals("VALUES 1", conn.nativeSQL("VALUES 1"));
        assertTrue(conn.isValid(0));
        assertSqlState("HY092", () -> conn.isValid(-1));

        assertNull(conn.getWarnings());
        conn.clearWarnings();

        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, conn.getHoldability());
        conn.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));

        assertTrue(conn.getTypeMap().isEmpty());
        assertEquals(0, conn.getNetworkTimeout());

        conn.setClientInfo("ApplicationName", "test");
        conn.setClientInfo(new Properties());
        assertNull(conn.getClientInfo("ApplicationName"));
        assertTrue(conn.getClientInfo().isEmpty());

        DriverTest.assertWrapper(conn, VsqlConnection.class);
    }

    @Test
    void unsupportedStatements() throws SQLException {
        try (var s = conn.prepareStatement("VALUES 1", Statement.NO_GENERATED_KEYS)) {
            assertNotNull(s);
        }
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> conn.prepareStatement("VALUES 1", Statement.RETURN_GENERATED_KEYS));
        assertThrows(SQLFeatureNotSupportedException.class, () -> conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertThrows(SQLFeatureNotSupportedException.class, () -> conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT));
    }

    @Test
    void autoCommit() throws SQLException {
        conn.setAutoCommit(true);
        assertSqlState("25000", () -> conn.releaseSavepoint(null));

        conn.setAutoCommit(false);
        // There's no transaction yet.
        conn.setAutoCommit(true);

        conn.setAutoCommit(false);
        Savepoint s = conn.setSavepoint();
        assertTrue(s.toString().startsWith("JDBC_SAVEPOINT_"), s.toString());
        assertEquals("foo", conn.setSavepoint("foo").toString());
        conn.rollback();
    }

    @Test
    void abort() throws SQLException {
        assertSqlState("HY009", () -> conn.abort(null));
        conn.abort(Runnable::run);
        assertTrue(conn.isClosed());
    }

    @Test
    void cantConnect() {
        Path file = tmp.resolve("no folder").resolve("test.vsql");
        assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:vsql:" + file));
    }

    /** Every error from closing is thrown, the rest are chained to the first. */
    @Test
    void closeErrors() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:vsql::memory:");
        c.setAutoCommit(false);
        VsqlStatement s = (VsqlStatement) c.createStatement();
        s.execute("VALUES 1");
        int dbc = c.unwrap(VsqlConnection.class).dbc();
        Native.freeHandle(Native.SQL_HANDLE_STMT, s.stmt);
        Native.endTran(dbc, false);
        Native.disconnect(dbc);
        Native.freeHandle(Native.SQL_HANDLE_DBC, dbc);

        SQLException e = assertThrows(SQLException.class, c::close);
        assertNotNull(e.getNextException());
        assertTrue(c.isClosed());
        assertFalse(c.isValid(0));
    }
}

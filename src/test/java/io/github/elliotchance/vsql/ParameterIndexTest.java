package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.sql.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Parameter indexes are checked against the number of parameters (#10). */
class ParameterIndexTest {
    private static final String THREE =
            "VALUES ROW(CAST(? AS INTEGER), CAST(? AS INTEGER), CAST(? AS INTEGER))";

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

    static String state(Executable e) {
        return assertThrows(SQLException.class, e).getSQLState();
    }

    /** Each setter checks the index is a parameter. */
    @Test
    void invalidIndex() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement(THREE)) {
            assertEquals(3, s.getParameterMetaData().getParameterCount());
            for (int index : new int[] {-1, 0, 4, 5}) {
                assertEquals("07009", state(() -> s.setInt(index, 1)), "index " + index);
                assertEquals("07009", state(() -> s.setString(index, "1")), "index " + index);
                assertEquals("07009", state(() -> s.setObject(index, 1)), "index " + index);
                assertEquals("07009", state(() -> s.setObject(index, 1, Types.INTEGER)), "index " + index);
                assertEquals("07009", state(() -> s.setNull(index, Types.INTEGER)), "index " + index);
            }

            // The parameters that are set are still used.
            s.setInt(1, 1);
            s.setInt(2, 2);
            s.setInt(3, 3);
            assertEquals("07009", state(() -> s.setInt(4, 4)));
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals(2, rs.getInt(2));
                assertEquals(3, rs.getInt(3));
                assertFalse(rs.next());
            }
        }
    }

    /** A statement without parameters doesn't have any index. */
    @Test
    void noParameters() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("VALUES 1")) {
            assertEquals(0, s.getParameterMetaData().getParameterCount());
            assertEquals("07009", state(() -> s.setInt(1, 1)));
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    /** Every parameter must be set, not only the ones up to the last set. */
    @Test
    void unsetParameter() throws SQLException {
        try (PreparedStatement s = conn.prepareStatement(THREE)) {
            SQLException e = assertThrows(SQLException.class, s::executeQuery);
            assertEquals("07002", e.getSQLState());
            assertTrue(e.getMessage().contains("parameter 1 "), e.getMessage());

            s.setInt(1, 1);
            s.setInt(2, 2);
            e = assertThrows(SQLException.class, s::executeQuery);
            assertEquals("07002", e.getSQLState());
            assertTrue(e.getMessage().contains("parameter 3 "), e.getMessage());

            s.setInt(3, 3);
            s.executeQuery().close();

            // clearParameters unsets all of them.
            s.clearParameters();
            assertEquals("07002", state(s::executeQuery));
        }
    }

    /** addBatch doesn't accept a set of parameters that isn't complete. */
    @Test
    void unsetParameterInBatch() throws SQLException {
        exec("CREATE TABLE foo (x INTEGER, y INTEGER)");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (x, y) VALUES (?, ?)")) {
            s.setInt(1, 1);
            s.setInt(2, 2);
            s.addBatch();
            s.clearParameters();
            s.setInt(1, 3);
            assertEquals("07002", state(s::addBatch));
            assertArrayEquals(new int[] {1}, s.executeBatch());
        }
    }
}

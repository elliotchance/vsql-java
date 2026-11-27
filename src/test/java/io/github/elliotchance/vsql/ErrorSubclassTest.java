package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.time.Period;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ErrorSubclassTest {
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

    @Test
    void notANumber() throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("VALUES 'abc'")) {
            assertTrue(rs.next());
            SQLDataException e = assertThrows(SQLDataException.class, () -> rs.getInt(1));
            assertEquals("22018", e.getSQLState());
            assertInstanceOf(NumberFormatException.class, e.getCause());
            assertFalse(e instanceof VsqlException);
        }
    }

    @Test
    void wrongType() throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("VALUES 12")) {
            assertTrue(rs.next());
            assertEquals("22018", assertThrows(SQLDataException.class, () -> rs.getDate(1)).getSQLState());
        }
    }

    @Test
    void outOfRange() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES CAST(100000 AS INTEGER)")) {
            assertTrue(rs.next());
            assertEquals("22003", assertThrows(SQLDataException.class, () -> rs.getShort(1)).getSQLState());
        }
    }

    @Test
    void invalidDatetime() {
        SQLDataException e = assertThrows(SQLDataException.class, () -> Values.parseDate("DATE '2022-13-45'"));
        assertEquals("22007", e.getSQLState());
        assertInstanceOf(DateTimeParseException.class, e.getCause());
    }

    @Test
    void periodWithDays() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("VALUES CAST(? AS INTEGER)")) {
            assertEquals("22015", assertThrows(SQLDataException.class,
                    () -> ps.setObject(1, Period.of(1, 2, 3))).getSQLState());
        }
    }

    @Test
    void noDatabase() {
        assertEquals("08001", assertThrows(SQLNonTransientConnectionException.class,
                () -> DriverManager.getConnection("jdbc:vsql:")).getSQLState());
    }

    @Test
    void otherClassesArePlain() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("VALUES CAST(? AS INTEGER)")) {
            SQLException e = assertThrows(SQLException.class, () -> ps.setInt(0, 1));
            assertEquals("07009", e.getSQLState());
            assertEquals(SQLException.class, e.getClass());
        }
    }
}

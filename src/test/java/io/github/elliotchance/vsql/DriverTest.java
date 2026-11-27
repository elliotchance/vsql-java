package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Wrapper;
import org.junit.jupiter.api.Test;

/** VsqlDriver and VsqlDataSource. */
class DriverTest {
    @Test
    void registrationFails() {
        SQLException e = new SQLException("can't register");
        ExceptionInInitializerError error = assertThrows(ExceptionInInitializerError.class,
                () -> VsqlDriver.register(() -> {
                    throw e;
                }));
        assertSame(e, error.getCause());
    }

    @Test
    void driver() throws SQLException {
        VsqlDriver d = new VsqlDriver();
        assertFalse(d.acceptsURL(null));
        assertFalse(d.acceptsURL("jdbc:other:x"));
        assertNull(d.connect("jdbc:other:x", null));
        assertEquals(3, d.getPropertyInfo("jdbc:vsql::memory:", null).length);
        assertEquals(0, d.getPropertyInfo("jdbc:other:x", null).length);
        assertEquals(Version.part(Version.DRIVER, 0), d.getMajorVersion());
        assertEquals(Version.part(Version.DRIVER, 1), d.getMinorVersion());
        assertFalse(d.jdbcCompliant());
        assertThrows(SQLFeatureNotSupportedException.class, d::getParentLogger);
    }

    @Test
    void dataSource() throws SQLException {
        VsqlDataSource ds = new VsqlDataSource();
        assertNull(ds.getUrl());
        assertEquals("08001", assertThrows(SQLException.class, ds::getConnection).getSQLState());

        ds.setUrl("jdbc:other:x");
        assertEquals("jdbc:other:x", ds.getUrl());
        assertEquals("08001", assertThrows(SQLException.class, ds::getConnection).getSQLState());

        ds.setUrl("jdbc:vsql::memory:");
        try (Connection c = ds.getConnection("user", "password")) {
            assertNotNull(c);
        }

        assertNull(ds.getLogWriter());
        PrintWriter out = new PrintWriter(new StringWriter());
        ds.setLogWriter(out);
        assertSame(out, ds.getLogWriter());
        ds.setLoginTimeout(5);
        assertEquals(5, ds.getLoginTimeout());
        assertThrows(SQLFeatureNotSupportedException.class, ds::getParentLogger);

        assertWrapper(ds, VsqlDataSource.class);
    }

    /** A wrapper unwraps to itself, and to nothing else. */
    static void assertWrapper(Wrapper w, Class<?> cls) throws SQLException {
        assertTrue(w.isWrapperFor(cls));
        assertSame(w, w.unwrap(cls));
        assertFalse(w.isWrapperFor(String.class));
        assertEquals("HY000", assertThrows(SQLException.class, () -> w.unwrap(String.class)).getSQLState());
    }

    /** A connection whose properties can't be set is closed. */
    @Test
    void configureFails() throws SQLException {
        VsqlConnection conn = DriverManager.getConnection("jdbc:vsql::memory:").unwrap(VsqlConnection.class);
        SQLException e = new SQLException("failed");
        assertSame(e, assertThrows(SQLException.class, () -> VsqlDriver.configure(conn, () -> {
            throw e;
        })));
        assertTrue(conn.isClosed());
    }
}

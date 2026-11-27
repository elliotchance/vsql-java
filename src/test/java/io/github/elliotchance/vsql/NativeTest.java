package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Linker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loading and calling libvsql, see Native. */
class NativeTest {
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    int dbc() throws SQLException {
        return conn.unwrap(VsqlConnection.class).dbc();
    }

    @Test
    void platforms() {
        assertEquals("linux-x86-64", Native.platform("Linux", "amd64"));
        assertEquals("macos-arm64", Native.platform("Mac OS X", "aarch64"));
        assertEquals("windows-x86-64", Native.platform("Windows 11", "x86_64"));
        assertEquals("linux-arm64", Native.platform("Linux", "arm64"));
        assertThrows(UnsatisfiedLinkError.class, () -> Native.platform("FreeBSD", "amd64"));
        assertThrows(UnsatisfiedLinkError.class, () -> Native.platform("Linux", "sparc"));
    }

    @Test
    void libraryFiles() {
        assertEquals("libvsql.so", Native.libraryFile("linux-arm64"));
        assertEquals("libvsql.dylib", Native.libraryFile("macos-arm64"));
        assertEquals("libvsql.dll", Native.libraryFile("windows-x86-64"));
    }

    @Test
    void readLibrary() {
        assertArrayEquals(new byte[] {1, 2}, Native.readLibrary(new ByteArrayInputStream(new byte[] {1, 2}), "x"));
        assertThrows(UnsatisfiedLinkError.class, () -> Native.readLibrary(null, "linux-sparc"));
        assertThrows(UncheckedIOException.class, () -> Native.readLibrary(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("can't read");
            }
        }, "x"));
    }

    @Test
    void missingRoutine() {
        assertThrows(UnsatisfiedLinkError.class,
                () -> Native.handle(Linker.nativeLinker(), name -> Optional.empty(), "SQLNope"));
    }

    @Test
    void callFails() {
        Error error = new Error("failed");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Native.call(() -> {
            throw error;
        }));
        assertEquals(error, e.getCause());
    }

    /** The thread that loaded libvsql never exits, even when interrupted. */
    @Test
    void loaderThread() throws InterruptedException {
        Thread loader = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("vsql"))
                .findFirst()
                .orElseThrow();
        loader.interrupt();
        for (int i = 0; i < 100 && loader.isInterrupted(); i++) {
            Thread.sleep(10);
        }
        assertFalse(loader.isInterrupted());
        Thread.sleep(50);
        assertTrue(loader.isAlive());
    }

    @Test
    void invalidHandle() {
        assertEquals("HY000", assertThrows(SQLException.class, () -> Native.closeCursor(-1234)).getSQLState());
    }

    @Test
    void transactionActiveOfInvalidHandle() {
        assertFalse(Native.transactionActive(-1234));
    }

    @Test
    void unsupportedFeature() throws SQLException {
        assertTrue(Native.getFeatureInfo(dbc(), "E011", null));
        assertFalse(Native.getFeatureInfo(dbc(), "S011", null));
    }

    @Test
    void errorWithoutDiagnostics() throws SQLException {
        int env = Native.allocEnv();
        try {
            SQLException e = assertThrows(SQLException.class,
                    () -> Native.check((short) -1, Native.SQL_HANDLE_ENV, env));
            assertEquals("HY000", e.getSQLState());
            assertEquals("unknown error", e.getMessage());
        } finally {
            Native.freeHandle(Native.SQL_HANDLE_ENV, env);
        }
    }

    @Test
    void unknownParameter() throws SQLException {
        int stmt = Native.allocHandle(Native.SQL_HANDLE_STMT, Native.SQL_HANDLE_DBC, dbc());
        try {
            Native.prepare(stmt, "VALUES CAST(? AS INTEGER)");
            Param p = new Param(Native.SQL_BIGINT, Native.SQL_BIGINT, 0, (short) 0, 1);
            assertThrows(IllegalArgumentException.class, () -> Native.execute(stmt, new Param[] {p}));
        } finally {
            Native.freeHandle(Native.SQL_HANDLE_STMT, stmt);
        }
    }

    /**
     * An identifier is at most 128 characters, but it's longer than that in
     * UTF-8 octets.
     */
    static final String NAME = "\u00c9".repeat(100);

    @Test
    void longNames() throws SQLException {
        try (Statement s = conn.createStatement()) {
            assertEquals("HY090", assertThrows(SQLException.class, () -> s.setCursorName("c".repeat(40000)))
                    .getSQLState());
            s.setCursorName(NAME);
            try (ResultSet rs = s.executeQuery("SELECT * FROM (VALUES 1) AS t (\"" + NAME + "\")")) {
                assertEquals(NAME, rs.getCursorName());
                assertEquals(NAME, rs.getMetaData().getColumnName(1));
            }
        }
    }

    @Test
    void longDatabaseName() {
        assertEquals("HY090", assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:vsql:" + "d".repeat(40000))).getSQLState());
    }

    @Test
    void longMessage() throws SQLException {
        String name = "t".repeat(600);
        try (Statement s = conn.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.execute("SELECT * FROM \"" + name + "\""));
            assertTrue(e.getMessage().contains(name), e.getMessage());

            s.execute("CREATE TABLE foo (x INTEGER)");
            e = assertThrows(SQLException.class, () -> s.execute("SELECT \"" + NAME + "\" FROM foo"));
            assertEquals(NAME, ((VsqlException) e).getDiagnostics().columnName());
        }
    }

    /** A statement that fails after a warning has two diagnostic records. */
    @Test
    void moreDiagnostics() throws SQLException {
        String sql = "SELECT CAST('abc' AS CHAR(1)), 1 / 0 FROM (VALUES 1) AS t (x)";
        try (Statement s = conn.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.execute(sql));
            assertNotNull(e.getNextException());
            assertFalse(((VsqlException) e).getDiagnostics().moreConditions());

            s.execute("SET TRANSACTION DIAGNOSTICS SIZE 1");
            e = assertThrows(SQLException.class, () -> s.execute(sql));
            assertNull(e.getNextException());
            assertTrue(assertInstanceOf(VsqlException.class, e).getDiagnostics().moreConditions());
        }
    }

    @Test
    void nullNumbers() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES (CAST(NULL AS DOUBLE PRECISION), CAST(NULL AS REAL))")) {
            assertTrue(rs.next());
            assertNull(rs.getObject(1));
            assertNull(rs.getObject(2));
        }
    }
}

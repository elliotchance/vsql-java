package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionPropertiesTest {
    @TempDir
    Path tmp;

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void defaults() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:")) {
            assertTrue(c.getAutoCommit());
            assertFalse(c.isReadOnly());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
        }
    }

    @Test
    void url() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                "jdbc:vsql::memory:;autoCommit=false;readOnly=true;transactionIsolation=SERIALIZABLE")) {
            assertFalse(c.getAutoCommit());
            assertTrue(c.isReadOnly());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
            assertEquals("jdbc:vsql::memory:;autoCommit=false;readOnly=true;transactionIsolation=SERIALIZABLE",
                    c.getMetaData().getURL());

            // The transaction (auto-commit is off) is READ ONLY.
            SQLException e = assertThrows(SQLException.class, () -> exec(c, "CREATE TABLE t (x INTEGER)"));
            assertEquals("25006", e.getSQLState());
        }
    }

    @Test
    void urlValues() throws SQLException {
        // Values aren't case sensitive, and an empty setting is ignored.
        try (Connection c = DriverManager.getConnection(
                "jdbc:vsql::memory:;readOnly=TRUE;;transactionIsolation=repeatable_read;")) {
            assertTrue(c.isReadOnly());
            assertTrue(c.getAutoCommit());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, c.getTransactionIsolation());
        }
        try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:;transactionIsolation=READ_UNCOMMITTED")) {
            assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, c.getTransactionIsolation());
        }
    }

    @Test
    void filePath() throws SQLException {
        Path file = tmp.resolve("test.vsql");
        try (Connection c = DriverManager.getConnection("jdbc:vsql:" + file + ";autoCommit=false")) {
            assertFalse(c.getAutoCommit());
            exec(c, "CREATE TABLE t (x INTEGER)");
            c.commit();
        }
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(tmp.resolve("test.vsql;autoCommit=false")));
    }

    @Test
    void properties() throws SQLException {
        Properties info = new Properties();
        info.setProperty("autoCommit", "false");
        info.setProperty("readOnly", "true");
        info.setProperty("transactionIsolation", "SERIALIZABLE");
        try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:", info)) {
            assertFalse(c.getAutoCommit());
            assertTrue(c.isReadOnly());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
        }

        // A value doesn't have to be a string.
        info = new Properties();
        info.put("readOnly", true);
        try (Connection c = new VsqlDriver().connect("jdbc:vsql::memory:", info)) {
            assertTrue(c.isReadOnly());
        }
    }

    @Test
    void urlReplacesProperties() throws SQLException {
        Properties info = new Properties();
        info.setProperty("readOnly", "true");
        info.setProperty("autoCommit", "false");
        try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:;readOnly=false", info)) {
            assertFalse(c.isReadOnly());
            assertFalse(c.getAutoCommit());
        }
    }

    @Test
    void unknownProperties() throws SQLException {
        // Properties can have others, such as user and password.
        Properties info = new Properties();
        info.setProperty("foo", "bar");
        try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:", info)) {
            assertTrue(c.getAutoCommit());
        }
        try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:", "user", "password")) {
            assertTrue(c.getAutoCommit());
        }

        // But not the URL. The names are case sensitive.
        assertEquals("08001", assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:vsql::memory:;foo=bar")).getSQLState());
        assertEquals("08001", assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:vsql::memory:;readonly=true")).getSQLState());
        assertEquals("08001", assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:vsql::memory:;readOnly")).getSQLState());
    }

    @Test
    void invalidValues() {
        for (String url : new String[] {
                "jdbc:vsql::memory:;autoCommit=yes",
                "jdbc:vsql::memory:;readOnly=",
                "jdbc:vsql::memory:;transactionIsolation=READ COMMITTED",
                "jdbc:vsql::memory:;transactionIsolation=2",
        }) {
            assertEquals("08001", assertThrows(SQLException.class, () -> DriverManager.getConnection(url))
                    .getSQLState(), url);
        }

        Properties info = new Properties();
        info.setProperty("readOnly", "maybe");
        assertEquals("08001", assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:vsql::memory:", info)).getSQLState());
    }

    @Test
    void noDatabase() {
        assertEquals("08001", assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:vsql:;readOnly=true")).getSQLState());
    }

    @Test
    void propertyInfo() throws SQLException {
        VsqlDriver driver = new VsqlDriver();
        DriverPropertyInfo[] info = driver.getPropertyInfo("jdbc:vsql::memory:", null);
        assertArrayEquals(new String[] {"autoCommit", "readOnly", "transactionIsolation"},
                Stream.of(info).map(i -> i.name).toArray(String[]::new));
        assertArrayEquals(new String[] {"true", "false", "READ_COMMITTED"},
                Stream.of(info).map(i -> i.value).toArray(String[]::new));
        assertArrayEquals(new String[] {"READ_UNCOMMITTED", "READ_COMMITTED", "REPEATABLE_READ", "SERIALIZABLE"},
                info[2].choices);
        for (DriverPropertyInfo i : info) {
            assertFalse(i.required);
            assertTrue(i.description != null && !i.description.isEmpty());
        }

        Properties props = new Properties();
        props.setProperty("autoCommit", "false");
        props.setProperty("readOnly", "true");
        info = driver.getPropertyInfo("jdbc:vsql::memory:;readOnly=false;transactionIsolation=SERIALIZABLE", props);
        assertArrayEquals(new String[] {"false", "false", "SERIALIZABLE"},
                Stream.of(info).map(i -> i.value).toArray(String[]::new));

        assertEquals(0, driver.getPropertyInfo("jdbc:other:x", null).length);
        assertThrows(SQLException.class, () -> driver.getPropertyInfo("jdbc:vsql::memory:;foo=bar", null));
    }

    @Test
    void dataSource() throws SQLException {
        VsqlDataSource ds = new VsqlDataSource("jdbc:vsql::memory:");
        assertTrue(ds.getAutoCommit());
        assertFalse(ds.isReadOnly());
        assertNull(ds.getTransactionIsolation());

        ds.setAutoCommit(false);
        ds.setReadOnly(true);
        ds.setTransactionIsolation("SERIALIZABLE");
        assertFalse(ds.getAutoCommit());
        assertTrue(ds.isReadOnly());
        assertEquals("SERIALIZABLE", ds.getTransactionIsolation());
        try (Connection c = ds.getConnection()) {
            assertFalse(c.getAutoCommit());
            assertTrue(c.isReadOnly());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
        }

        // The URL replaces them.
        ds.setUrl("jdbc:vsql::memory:;readOnly=false");
        try (Connection c = ds.getConnection()) {
            assertFalse(c.isReadOnly());
            assertFalse(c.getAutoCommit());
        }

        ds.setTransactionIsolation("NOPE");
        assertEquals("08001", assertThrows(SQLException.class, ds::getConnection).getSQLState());
        ds.setTransactionIsolation(null);
        assertNull(ds.getTransactionIsolation());
        try (Connection c = ds.getConnection()) {
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
        }
    }
}

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

class SchemaAndCatalogTest {
    @TempDir
    Path tmp;

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

    Object scalar(String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getObject(1);
        }
    }

    @Test
    void defaultSchemaAndCatalog() throws SQLException {
        assertEquals("PUBLIC", conn.getSchema());
        assertEquals(":memory:", conn.getCatalog());
    }

    @Test
    void setSchema() throws SQLException {
        exec("CREATE SCHEMA foo");
        exec("CREATE TABLE foo.t (x INTEGER)");
        exec("INSERT INTO foo.t (x) VALUES (123)");

        conn.setSchema("FOO");
        assertEquals("FOO", conn.getSchema());
        assertEquals(123, scalar("SELECT x FROM t"));
        assertEquals("FOO", scalar("VALUES CURRENT_SCHEMA"));

        conn.setSchema("PUBLIC");
        assertEquals("PUBLIC", conn.getSchema());
    }

    @Test
    void setSchemaIsTheExactName() throws SQLException {
        exec("CREATE SCHEMA foo");
        exec("CREATE SCHEMA \"bar\"");

        // A name isn't an identifier, so "foo" isn't FOO.
        SQLException e = assertThrows(SQLException.class, () -> conn.setSchema("foo"));
        assertEquals("3F000", e.getSQLState());
        assertEquals("PUBLIC", conn.getSchema());

        conn.setSchema("bar");
        assertEquals("bar", conn.getSchema());
    }

    @Test
    void setSchemaQuotesTheName() throws SQLException {
        // A double quote, a single quote, a period and a space are all part of
        // the name.
        exec("CREATE SCHEMA \"it's a \"\"b\"\".c\"");
        conn.setSchema("it's a \"b\".c");
        assertEquals("it's a \"b\".c", conn.getSchema());
        exec("CREATE TABLE t (x INTEGER)");
        exec("INSERT INTO \"it's a \"\"b\"\".c\".t (x) VALUES (1)");
        assertEquals(1, scalar("SELECT x FROM t"));
    }

    @Test
    void setSchemaThatDoesNotExist() throws SQLException {
        SQLException e = assertThrows(SQLException.class, () -> conn.setSchema("NOPE"));
        assertEquals("3F000", e.getSQLState());
        assertEquals("PUBLIC", conn.getSchema());

        e = assertThrows(SQLException.class, () -> conn.setSchema(""));
        assertEquals("3F000", e.getSQLState());

        e = assertThrows(SQLException.class, () -> conn.setSchema(null));
        assertEquals("HY009", e.getSQLState());
        assertEquals("PUBLIC", conn.getSchema());
    }

    @Test
    void setSchemaInTransaction() throws SQLException {
        exec("CREATE SCHEMA foo");
        exec("CREATE TABLE foo.t (x INTEGER)");
        conn.setAutoCommit(false);
        exec("INSERT INTO foo.t (x) VALUES (1)");
        conn.setSchema("FOO");
        exec("INSERT INTO t (x) VALUES (2)");
        assertEquals(2, scalar("SELECT COUNT(*) FROM t"));
        conn.rollback();
        assertEquals(0, scalar("SELECT COUNT(*) FROM t"));
        assertEquals("FOO", conn.getSchema());
    }

    @Test
    void setCatalog() throws SQLException {
        conn.setCatalog(":memory:");
        assertEquals(":memory:", conn.getCatalog());

        SQLException e = assertThrows(SQLException.class, () -> conn.setCatalog("nope"));
        assertEquals("3D000", e.getSQLState());
        assertEquals(":memory:", conn.getCatalog());

        e = assertThrows(SQLException.class, () -> conn.setCatalog(null));
        assertEquals("HY009", e.getSQLState());
    }

    @Test
    void catalogOfFile() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:vsql:" + tmp.resolve("test.vsql"))) {
            assertEquals("test", c.getCatalog());

            // The exact name, which isn't the identifier TEST.
            c.setCatalog("test");
            assertEquals("test", c.getCatalog());
            SQLException e = assertThrows(SQLException.class, () -> c.setCatalog("TEST"));
            assertEquals("3D000", e.getSQLState());

            // The catalog doesn't change when the schema is set.
            c.setSchema("PUBLIC");
            assertEquals("test", c.getCatalog());
        }
    }

    @Test
    void closed() throws SQLException {
        conn.close();
        assertThrows(SQLException.class, () -> conn.getSchema());
        assertThrows(SQLException.class, () -> conn.setSchema("PUBLIC"));
        assertThrows(SQLException.class, () -> conn.getCatalog());
        assertThrows(SQLException.class, () -> conn.setCatalog(":memory:"));
    }
}

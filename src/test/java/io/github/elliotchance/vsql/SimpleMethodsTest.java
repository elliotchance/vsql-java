package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimpleMethodsTest {
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

    void insertNumbers(int n) throws SQLException {
        exec("CREATE TABLE foo (x INTEGER)");
        for (int i = 1; i <= n; i++) {
            exec("INSERT INTO foo (x) VALUES (" + i + ")");
        }
    }

    @Test
    void positions() throws SQLException {
        insertNumbers(3);
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
            assertTrue(rs.isBeforeFirst());
            // It doesn't move the cursor, so it can be called again.
            assertTrue(rs.isBeforeFirst());
            assertFalse(rs.isFirst());
            assertFalse(rs.isAfterLast());
            assertEquals(0, rs.getRow());
            assertEquals("24000", assertThrows(SQLException.class, () -> rs.getInt(1)).getSQLState());

            List<Integer> xs = new ArrayList<>();
            while (rs.next()) {
                xs.add(rs.getInt(1));
                assertEquals(xs.size(), rs.getRow());
                assertEquals(xs.size() == 1, rs.isFirst());
                assertFalse(rs.isBeforeFirst());
                assertFalse(rs.isAfterLast());
            }
            assertEquals(List.of(1, 2, 3), xs);
            assertTrue(rs.isAfterLast());
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.isFirst());
            assertEquals(0, rs.getRow());
            assertFalse(rs.next());
            assertTrue(rs.isAfterLast());
        }

        // Without isBeforeFirst.
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
            assertFalse(rs.isAfterLast());
            assertTrue(rs.next());
            assertTrue(rs.isFirst());
            assertFalse(rs.isBeforeFirst());
            assertTrue(rs.next());
            assertFalse(rs.isFirst());
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void emptyResultSet() throws SQLException {
        exec("CREATE TABLE foo (x INTEGER)");
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.isAfterLast());
            assertFalse(rs.isFirst());
            assertFalse(rs.isClosed());
            assertFalse(rs.next());
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.isAfterLast());
            assertEquals(0, rs.getRow());
        }
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
            assertFalse(rs.next());
            assertFalse(rs.isBeforeFirst());
            assertFalse(rs.isAfterLast());
        }
    }

    @Test
    void maxRows() throws SQLException {
        insertNumbers(5);
        try (Statement s = conn.createStatement()) {
            s.setMaxRows(2);
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
                assertTrue(rs.isBeforeFirst());
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertFalse(rs.next());
                assertTrue(rs.isAfterLast());
            }

            s.setMaxRows(1);
            try (ResultSet rs = s.executeQuery("SELECT x FROM foo ORDER BY x")) {
                assertTrue(rs.isBeforeFirst());
                assertTrue(rs.next());
                assertTrue(rs.isFirst());
                assertFalse(rs.next());
                assertTrue(rs.isAfterLast());
            }
        }
    }

    @Test
    void wasNull() throws SQLException {
        exec("CREATE TABLE foo (x INTEGER)");
        exec("INSERT INTO foo (x) VALUES (NULL)");
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM foo")) {
            // Looking ahead doesn't read a value.
            assertTrue(rs.isBeforeFirst());
            assertFalse(rs.wasNull());
            assertTrue(rs.next());
            assertFalse(rs.wasNull());
            assertEquals(0, rs.getInt(1));
            assertTrue(rs.wasNull());
            assertFalse(rs.next());
            assertTrue(rs.isAfterLast());
        }
    }

    @Test
    void metadataResultSet() throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTypeInfo()) {
            assertTrue(rs.isBeforeFirst());
            assertTrue(rs.next());
            assertTrue(rs.isFirst());
            assertFalse(rs.isBeforeFirst());
            while (rs.next()) {
                assertFalse(rs.isFirst());
            }
            assertTrue(rs.isAfterLast());
        }
    }

    @Test
    void closedResultSet() throws SQLException {
        insertNumbers(1);
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT x FROM foo");
        assertTrue(rs.isBeforeFirst());
        rs.close();
        assertThrows(SQLException.class, rs::isBeforeFirst);
        assertThrows(SQLException.class, rs::isAfterLast);
        assertThrows(SQLException.class, rs::isFirst);
        assertThrows(SQLException.class, rs::next);
        s.close();
    }

    @Test
    void getStreams() throws Exception {
        exec("CREATE TABLE foo (v VARCHAR(10), n VARCHAR(10))");
        exec("INSERT INTO foo (v, n) VALUES ('héllo', NULL)");
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT v, n FROM foo")) {
            assertTrue(rs.next());
            // A character that isn't ASCII is a "?".
            assertArrayEquals("h?llo".getBytes(StandardCharsets.US_ASCII), rs.getAsciiStream(1).readAllBytes());
            assertArrayEquals("h?llo".getBytes(StandardCharsets.US_ASCII), rs.getAsciiStream("V").readAllBytes());
            assertEquals("héllo", new BufferedReader(rs.getNCharacterStream(1)).readLine());
            assertEquals("héllo", new BufferedReader(rs.getNCharacterStream("v")).readLine());
            assertNull(rs.getAsciiStream(2));
            assertTrue(rs.wasNull());
            assertNull(rs.getNCharacterStream("N"));
            assertEquals("42S22", assertThrows(SQLException.class, () -> rs.getAsciiStream("x")).getSQLState());
        }
    }

    static InputStream ascii(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    List<String> column(String name) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT " + name + " FROM foo ORDER BY n")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    @Test
    void setAsciiStream() throws SQLException {
        exec("CREATE TABLE foo (n INTEGER, c CLOB(100))");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (n, c) VALUES (?, ?)")) {
            s.setInt(1, 1);
            s.setAsciiStream(2, ascii("hello"));
            s.executeUpdate();

            s.setInt(1, 2);
            s.setAsciiStream(2, ascii("hello"), 3);
            s.executeUpdate();

            s.setInt(1, 3);
            s.setAsciiStream(2, ascii("hello"), 4L);
            s.executeUpdate();

            s.setInt(1, 4);
            s.setAsciiStream(2, null);
            s.executeUpdate();

            s.setInt(1, 5);
            s.setAsciiStream(2, ascii(""), 0);
            s.executeUpdate();

            assertEquals("22023",
                    assertThrows(SQLException.class, () -> s.setAsciiStream(2, ascii("ab"), 3)).getSQLState());
            assertEquals("07009",
                    assertThrows(SQLException.class, () -> s.setAsciiStream(0, ascii("ab"))).getSQLState());
        }
        assertEquals(Arrays.asList("hello", "hel", "hell", null, ""), column("c"));
    }

    @Test
    void setNCharacterStream() throws SQLException {
        exec("CREATE TABLE foo (n INTEGER, c CLOB(100))");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (n, c) VALUES (?, ?)")) {
            s.setInt(1, 1);
            s.setNCharacterStream(2, new StringReader("héllo"));
            s.executeUpdate();

            s.setInt(1, 2);
            s.setNCharacterStream(2, new StringReader("héllo"), 2);
            s.executeUpdate();

            s.setInt(1, 3);
            s.setNCharacterStream(2, null);
            s.executeUpdate();

            assertEquals("22023", assertThrows(SQLException.class,
                    () -> s.setNCharacterStream(2, new StringReader("ab"), 3)).getSQLState());
        }
        assertEquals(Arrays.asList("héllo", "hé", null), column("c"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void setUnicodeStream() throws SQLException {
        exec("CREATE TABLE foo (n INTEGER, v VARCHAR(100))");
        byte[] utf8 = "héllo".getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (n, v) VALUES (?, ?)")) {
            s.setInt(1, 1);
            s.setUnicodeStream(2, new ByteArrayInputStream(utf8), utf8.length);
            s.executeUpdate();

            // The length is in octets.
            s.setInt(1, 2);
            s.setUnicodeStream(2, new ByteArrayInputStream(utf8), 3);
            s.executeUpdate();

            s.setInt(1, 3);
            s.setUnicodeStream(2, null, 0);
            s.executeUpdate();

            assertEquals("22023", assertThrows(SQLException.class,
                    () -> s.setUnicodeStream(2, new ByteArrayInputStream(utf8), 100)).getSQLState());
        }
        assertEquals(Arrays.asList("héllo", "hé", null), column("v"));
    }

    @Test
    void setURL() throws Exception {
        exec("CREATE TABLE foo (n INTEGER, v VARCHAR(100))");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (n, v) VALUES (?, ?)")) {
            s.setInt(1, 1);
            s.setURL(2, URI.create("https://example.com/a?b=c").toURL());
            s.executeUpdate();

            s.setInt(1, 2);
            s.setURL(2, null);
            s.executeUpdate();
        }
        assertEquals(Arrays.asList("https://example.com/a?b=c", null), column("v"));
    }
}

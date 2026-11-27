package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** VsqlBlob and VsqlClob. */
class BlobClobTest {
    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** All of a reader. */
    static String read(Reader r) throws IOException {
        StringWriter w = new StringWriter();
        r.transferTo(w);
        return w.toString();
    }

    static void assertSqlState(String sqlstate, Executable e) {
        assertEquals(sqlstate, assertThrows(SQLException.class, e).getSQLState());
    }

    /** A Blob or Clob (of type) that's longer than an array can be. */
    static <T> T tooLong(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getName().equals("length")) {
                        return Long.MAX_VALUE;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    @Test
    void blob() throws SQLException, IOException {
        VsqlBlob b = new VsqlBlob(bytes("abcdef"));
        assertSqlState("22011", () -> b.getBytes(0, 1));
        assertSqlState("22011", () -> b.getBytes(8, 1));
        assertSqlState("22011", () -> b.getBytes(1, -1));

        try (InputStream in = b.getBinaryStream()) {
            assertArrayEquals(bytes("abcdef"), in.readAllBytes());
        }
        try (InputStream in = b.getBinaryStream(2, 3)) {
            assertArrayEquals(bytes("bcd"), in.readAllBytes());
        }
        assertSqlState("22011", () -> b.getBinaryStream(2, -1));
        assertSqlState("22011", () -> b.getBinaryStream(2, 6));

        assertEquals(3, b.position(new VsqlBlob(bytes("cd")), 1));
        assertSqlState("22001", () -> b.position(tooLong(Blob.class), 1));

        assertSqlState("22011", () -> b.setBytes(1, bytes("x"), -1, 1));
        assertSqlState("22011", () -> b.setBytes(1, bytes("x"), 0, -1));
        assertSqlState("22011", () -> b.setBytes(1, bytes("x"), 0, 2));
        // Within the array, then past the end of it.
        assertEquals(1, b.setBytes(2, bytes("X")));
        assertEquals(2, b.setBytes(7, bytes("gh")));
        assertArrayEquals(bytes("aXcdefgh"), b.getBytes(1, 100));

        try (OutputStream out = b.setBinaryStream(9)) {
            out.write('i');
            out.write(bytes("jk"));
        }
        assertArrayEquals(bytes("aXcdefghijk"), b.getBytes(1, 100));

        assertSqlState("22011", () -> b.truncate(-1));
        assertSqlState("22011", () -> b.truncate(100));
        b.truncate(2);
        assertEquals(2, b.length());

        OutputStream out = b.setBinaryStream(1);
        b.free();
        assertThrows(IOException.class, () -> out.write('x'));
        assertSqlState("HY010", b::length);
    }

    @Test
    void clob() throws SQLException, IOException {
        VsqlClob c = new VsqlClob("abcdef");
        assertSqlState("22011", () -> c.getSubString(0, 1));
        assertSqlState("22011", () -> c.getSubString(8, 1));
        assertSqlState("22011", () -> c.getSubString(1, -1));

        try (Reader r = c.getCharacterStream()) {
            assertEquals("abcdef", BlobClobTest.read(r));
        }
        try (Reader r = c.getCharacterStream(2, 3)) {
            assertEquals("bcd", BlobClobTest.read(r));
        }
        assertSqlState("22011", () -> c.getCharacterStream(2, -1));
        assertSqlState("22011", () -> c.getCharacterStream(2, 6));
        try (InputStream in = c.getAsciiStream()) {
            assertArrayEquals(bytes("abcdef"), in.readAllBytes());
        }

        assertEquals(3, c.position(new VsqlClob("cd"), 1));
        assertEquals(-1, c.position("x", 1));
        assertSqlState("22001", () -> c.position(tooLong(Clob.class), 1));

        assertSqlState("22011", () -> c.setString(1, "x", -1, 1));
        assertSqlState("22011", () -> c.setString(1, "x", 0, -1));
        assertSqlState("22011", () -> c.setString(1, "x", 0, 2));

        try (OutputStream out = c.setAsciiStream(7)) {
            out.write('g');
        }
        try (Writer w = c.setCharacterStream(8)) {
            w.write("hi");
            w.flush();
        }
        assertEquals("abcdefghi", c.getSubString(1, 100));

        assertSqlState("22011", () -> c.truncate(-1));
        assertSqlState("22011", () -> c.truncate(100));
        c.truncate(2);
        assertEquals("ab", c.toString());

        Writer w = c.setCharacterStream(1);
        c.free();
        assertThrows(IOException.class, () -> w.write("x"));
        assertSqlState("HY010", c::length);
        assertEquals("", c.toString());
    }
}

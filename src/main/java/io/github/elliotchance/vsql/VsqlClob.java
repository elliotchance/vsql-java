package io.github.elliotchance.vsql;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * A CHARACTER LARGE OBJECT, which is a copy of the value in memory (vsql
 * doesn't have locators). Changing it doesn't change the database: it's a
 * value for a parameter, see {@link java.sql.Connection#createClob()}.
 * Positions and lengths are in Java chars.
 */
public final class VsqlClob implements Clob {
    private StringBuilder data;

    VsqlClob(String data) {
        this.data = new StringBuilder(data);
    }

    private void check() throws SQLException {
        if (data == null) {
            throw new SQLException("vsql: the Clob has been freed", "HY010");
        }
    }

    /** The index of pos (starting at 1), which can be at most at the end plus 1. */
    private int index(long pos) throws SQLException {
        check();
        if (pos < 1 || pos > data.length() + 1) {
            throw new SQLException("vsql: invalid position " + pos, "22011");
        }
        return (int) (pos - 1);
    }

    @Override
    public long length() throws SQLException {
        check();
        return data.length();
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        int i = index(pos);
        if (length < 0) {
            throw new SQLException("vsql: invalid length " + length, "22011");
        }
        return data.substring(i, i + Math.min(length, data.length() - i));
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        check();
        return new StringReader(data.toString());
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        int i = index(pos);
        if (length < 0 || i + length > data.length()) {
            throw new SQLException("vsql: invalid length " + length, "22011");
        }
        return new StringReader(data.substring(i, (int) (i + length)));
    }

    /** The characters as ASCII (any other character is "?"). */
    @Override
    public InputStream getAsciiStream() throws SQLException {
        check();
        return new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public long position(String pattern, long start) throws SQLException {
        int i = data.indexOf(pattern, index(start));
        return i < 0 ? -1 : i + 1;
    }

    @Override
    public long position(Clob pattern, long start) throws SQLException {
        return position(Values.string(pattern), start);
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        return setString(pos, str, 0, str.length());
    }

    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        int i = index(pos);
        if (offset < 0 || len < 0 || offset + len > str.length()) {
            throw new SQLException("vsql: invalid offset or length", "22011");
        }
        data.replace(i, Math.min(i + len, data.length()), str.substring(offset, offset + len));
        return len;
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        Writer w = setCharacterStream(pos);
        return new OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                w.write(b & 0x7f);
            }
        };
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        long[] at = {index(pos) + 1};
        return new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws java.io.IOException {
                try {
                    at[0] += setString(at[0], new String(cbuf, off, len));
                } catch (SQLException e) {
                    throw new java.io.IOException(e);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        check();
        if (len < 0 || len > data.length()) {
            throw new SQLException("vsql: invalid length " + len, "22011");
        }
        data.setLength((int) len);
    }

    @Override
    public void free() {
        data = null;
    }

    @Override
    public String toString() {
        return data == null ? "" : data.toString();
    }
}

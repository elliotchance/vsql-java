package io.github.elliotchance.vsql;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * A BINARY LARGE OBJECT, which is a copy of the value in memory (vsql doesn't
 * have locators). Changing it doesn't change the database: it's a value for a
 * parameter, see {@link java.sql.Connection#createBlob()}.
 */
public final class VsqlBlob implements Blob {
    private byte[] data;
    private int length;

    VsqlBlob(byte[] data) {
        this.data = data;
        this.length = data.length;
    }

    private void check() throws SQLException {
        if (data == null) {
            throw new SQLException("vsql: the Blob has been freed", "HY010");
        }
    }

    /** The index of pos (starting at 1), which can be at most at the end plus 1. */
    private int index(long pos, long max) throws SQLException {
        check();
        if (pos < 1 || pos > max + 1) {
            throw new SQLException("vsql: invalid position " + pos, "22011");
        }
        return (int) (pos - 1);
    }

    @Override
    public long length() throws SQLException {
        check();
        return length;
    }

    @Override
    public byte[] getBytes(long pos, int len) throws SQLException {
        int i = index(pos, length);
        if (len < 0) {
            throw new SQLException("vsql: invalid length " + len, "22011");
        }
        return Arrays.copyOfRange(data, i, i + Math.min(len, length - i));
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        check();
        return new ByteArrayInputStream(data, 0, length);
    }

    @Override
    public InputStream getBinaryStream(long pos, long len) throws SQLException {
        int i = index(pos, length);
        if (len < 0 || i + len > length) {
            throw new SQLException("vsql: invalid length " + len, "22011");
        }
        return new ByteArrayInputStream(data, i, (int) len);
    }

    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        int from = index(start, length);
        for (int i = from; i + pattern.length <= length; i++) {
            if (Arrays.equals(data, i, i + pattern.length, pattern, 0, pattern.length)) {
                return i + 1;
            }
        }
        return -1;
    }

    @Override
    public long position(Blob pattern, long start) throws SQLException {
        return position(Values.bytes(pattern), start);
    }

    @Override
    public int setBytes(long pos, byte[] bytes) throws SQLException {
        return setBytes(pos, bytes, 0, bytes.length);
    }

    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        int i = index(pos, length);
        if (offset < 0 || len < 0 || offset + len > bytes.length) {
            throw new SQLException("vsql: invalid offset or length", "22011");
        }
        if (i + len > data.length) {
            data = Arrays.copyOf(data, Math.max(i + len, data.length * 2));
        }
        System.arraycopy(bytes, offset, data, i, len);
        length = Math.max(length, i + len);
        return len;
    }

    @Override
    public OutputStream setBinaryStream(long pos) throws SQLException {
        long[] at = {index(pos, length) + 1};
        return new OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                try {
                    at[0] += setBytes(at[0], b, off, len);
                } catch (SQLException e) {
                    throw new java.io.IOException(e);
                }
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        check();
        if (len < 0 || len > length) {
            throw new SQLException("vsql: invalid length " + len, "22011");
        }
        length = (int) len;
    }

    @Override
    public void free() {
        data = null;
    }
}

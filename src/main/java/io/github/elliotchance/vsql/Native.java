package io.github.elliotchance.vsql;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.LockSupport;

/**
 * Native is the only code that calls libvsql (see libvsql.h), with the Foreign
 * Function and Memory API. Each method is a thin wrapper around a libvsql
 * routine, with handles as ints, and an SQLException from the first diagnostic
 * record of the handle.
 *
 * <p>libvsql is in the jar for each OS and architecture (see
 * scripts/build-libvsql.sh), and it's copied to a file (named by its checksum)
 * in the temporary folder to be loaded. The system property vsql.library.path
 * is the path of another libvsql to use instead.
 */
// Loading and calling libvsql are restricted methods (which need
// --enable-native-access).
@SuppressWarnings("restricted")
final class Native {
    // The constants from libvsql.h.
    static final short SQL_SUCCESS = 0;
    static final short SQL_SUCCESS_WITH_INFO = 1;
    static final short SQL_NO_DATA = 100;
    static final short SQL_INVALID_HANDLE = -2;
    static final int SQL_NULL_DATA = -1;

    static final short SQL_HANDLE_ENV = 1;
    static final short SQL_HANDLE_DBC = 2;
    static final short SQL_HANDLE_STMT = 3;

    static final short SQL_CHAR = 1;
    static final short SQL_NUMERIC = 2;
    static final short SQL_DECIMAL = 3;
    static final short SQL_INTEGER = 4;
    static final short SQL_SMALLINT = 5;
    static final short SQL_FLOAT = 6;
    static final short SQL_REAL = 7;
    static final short SQL_DOUBLE = 8;
    static final short SQL_VARCHAR = 12;
    static final short SQL_BOOLEAN = 16;
    static final short SQL_BIGINT = 25;
    static final short SQL_TYPE_DATE = 91;
    static final short SQL_TYPE_TIME = 92;
    static final short SQL_TYPE_TIMESTAMP = 93;
    static final short SQL_TYPE_TIME_WITH_TIMEZONE = 94;
    static final short SQL_TYPE_TIMESTAMP_WITH_TIMEZONE = 95;

    static final int SQL_ATTR_AUTOCOMMIT = 102;
    static final int SQL_AUTOCOMMIT_OFF = 0;
    static final int SQL_AUTOCOMMIT_ON = 1;
    static final short SQL_COMMIT = 0;
    static final short SQL_ROLLBACK = 1;

    static final int SQL_TRANSACTION_READ_ONLY = 1;
    static final int SQL_TRANSACTION_READ_WRITE = 2;
    static final int SQL_TRANSACTION_READ_UNCOMMITTED = 1;
    static final int SQL_TRANSACTION_READ_COMMITTED = 2;

    static final short SQL_PARAM_MODE_IN = 1;
    static final short SQL_RESET_PARAMS = 3;
    static final short SQL_NO_NULLS = 0;

    private static final MethodHandle ALLOC_HANDLE;
    private static final MethodHandle FREE_HANDLE;
    private static final MethodHandle CONNECT;
    private static final MethodHandle DISCONNECT;
    private static final MethodHandle SET_CONNECT_ATTR;
    private static final MethodHandle START_TRAN;
    private static final MethodHandle END_TRAN;
    private static final MethodHandle EXEC_DIRECT;
    private static final MethodHandle PREPARE;
    private static final MethodHandle BIND_PARAMETER;
    private static final MethodHandle EXECUTE;
    private static final MethodHandle ROW_COUNT;
    private static final MethodHandle NUM_RESULT_COLS;
    private static final MethodHandle DESCRIBE_COL;
    private static final MethodHandle FETCH;
    private static final MethodHandle GET_DATA;
    private static final MethodHandle CLOSE_CURSOR;
    private static final MethodHandle FREE_STMT;
    private static final MethodHandle GET_DIAG_REC;

    // libvsql reads the value of SQL_ATTR_AUTOCOMMIT after SQLSetConnectAttr
    // returns, so it's never freed.
    private static final MemorySegment AUTOCOMMIT_ON =
            Arena.global().allocateFrom(JAVA_INT, SQL_AUTOCOMMIT_ON);
    private static final MemorySegment AUTOCOMMIT_OFF =
            Arena.global().allocateFrom(JAVA_INT, SQL_AUTOCOMMIT_OFF);

    static {
        SymbolLookup lib = load(libraryPath());
        Linker linker = Linker.nativeLinker();
        ALLOC_HANDLE = handle(linker, lib, "SQLAllocHandle", JAVA_SHORT, JAVA_INT, ADDRESS);
        FREE_HANDLE = handle(linker, lib, "SQLFreeHandle", JAVA_SHORT, JAVA_INT);
        CONNECT = handle(linker, lib, "SQLConnect",
                JAVA_INT, ADDRESS, JAVA_SHORT, ADDRESS, JAVA_SHORT, ADDRESS, JAVA_SHORT);
        DISCONNECT = handle(linker, lib, "SQLDisconnect", JAVA_INT);
        SET_CONNECT_ATTR = handle(linker, lib, "SQLSetConnectAttr", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT);
        START_TRAN = handle(linker, lib, "SQLStartTran", JAVA_SHORT, JAVA_INT, JAVA_INT, JAVA_INT);
        END_TRAN = handle(linker, lib, "SQLEndTran", JAVA_SHORT, JAVA_INT, JAVA_SHORT);
        EXEC_DIRECT = handle(linker, lib, "SQLExecDirect", JAVA_INT, ADDRESS, JAVA_INT);
        PREPARE = handle(linker, lib, "SQLPrepare", JAVA_INT, ADDRESS, JAVA_INT);
        BIND_PARAMETER = handle(linker, lib, "SQLBindParameter", JAVA_INT, JAVA_SHORT, JAVA_SHORT,
                JAVA_SHORT, JAVA_SHORT, JAVA_INT, JAVA_SHORT, ADDRESS, JAVA_INT, ADDRESS);
        EXECUTE = handle(linker, lib, "SQLExecute", JAVA_INT);
        ROW_COUNT = handle(linker, lib, "SQLRowCount", JAVA_INT, ADDRESS);
        NUM_RESULT_COLS = handle(linker, lib, "SQLNumResultCols", JAVA_INT, ADDRESS);
        DESCRIBE_COL = handle(linker, lib, "SQLDescribeCol", JAVA_INT, JAVA_SHORT, ADDRESS,
                JAVA_SHORT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        FETCH = handle(linker, lib, "SQLFetch", JAVA_INT);
        GET_DATA = handle(linker, lib, "SQLGetData",
                JAVA_INT, JAVA_SHORT, JAVA_SHORT, ADDRESS, JAVA_INT, ADDRESS);
        CLOSE_CURSOR = handle(linker, lib, "SQLCloseCursor", JAVA_INT);
        FREE_STMT = handle(linker, lib, "SQLFreeStmt", JAVA_INT, JAVA_SHORT);
        GET_DIAG_REC = handle(linker, lib, "SQLGetDiagRec", JAVA_SHORT, JAVA_INT, JAVA_SHORT,
                ADDRESS, ADDRESS, ADDRESS, JAVA_SHORT, ADDRESS);

        // libvsql initializes itself (and its garbage collector) in the first
        // routine that's called, if it wasn't when it was loaded (see load).
        // That isn't thread safe: another thread that calls a routine at the
        // same time can use it before it's initialized
        // (https://github.com/elliotchance/vsql/issues/710). So the first
        // routine is called here, once, before any other (the JVM initializes
        // a class once, and other threads wait for it).
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment env = arena.allocate(JAVA_INT);
            if ((short) ALLOC_HANDLE.invokeExact(SQL_HANDLE_ENV, 0, env) == SQL_SUCCESS) {
                short rc = (short) FREE_HANDLE.invokeExact(SQL_HANDLE_ENV, env.get(JAVA_INT, 0));
            }
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    /**
     * Loads libvsql on a thread that never exits. libvsql initializes itself
     * and its garbage collector when it's loaded, and the collector treats the
     * thread that loaded it as the main thread, for good. If that thread
     * exits, the collector aborts on macOS ("thread_suspend failed") when a
     * new thread reuses its ID.
     */
    private static SymbolLookup load(Path path) {
        Loader loader = new Loader(path);
        Thread.ofPlatform().daemon().name("vsql").start(loader);
        try {
            return loader.lib.get();
        } catch (ExecutionException e) {
            throw new ExceptionInInitializerError(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The thread that loads libvsql. It's a class rather than a lambda,
     * because a lambda would be a method of Native, which waits for Native to
     * be initialized.
     */
    private static final class Loader implements Runnable {
        private final Path path;
        final CompletableFuture<SymbolLookup> lib = new CompletableFuture<>();

        Loader(Path path) {
            this.path = path;
        }

        @Override
        public void run() {
            try {
                lib.complete(SymbolLookup.libraryLookup(path, Arena.global()));
            } catch (Throwable t) {
                lib.completeExceptionally(t);
                return;
            }
            while (true) {
                LockSupport.park();
            }
        }
    }

    private Native() {
    }

    /** A call to libvsql, see VsqlConnection.close. */
    interface Call {
        void run() throws SQLException;
    }

    private static MethodHandle handle(Linker linker, SymbolLookup lib, String name,
            java.lang.foreign.MemoryLayout... args) {
        MemorySegment symbol = lib.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("vsql: libvsql has no " + name));
        return linker.downcallHandle(symbol, FunctionDescriptor.of(JAVA_SHORT, args));
    }

    /** The OS and architecture, as the name of the folder of its libvsql. */
    static String platform() {
        String name = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String os = name.startsWith("linux") ? "linux"
                : name.startsWith("mac") ? "macos"
                : name.startsWith("windows") ? "windows"
                : null;
        String arch = switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86-64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (os == null || arch == null) {
            throw new UnsatisfiedLinkError("vsql: " + System.getProperty("os.name") + " ("
                    + System.getProperty("os.arch") + ") isn't supported");
        }

        return os + "-" + arch;
    }

    private static Path libraryPath() {
        String override = System.getProperty("vsql.library.path");
        if (override != null) {
            return Path.of(override);
        }

        String platform = platform();
        String file = switch (platform.substring(0, platform.indexOf('-'))) {
            case "linux" -> "libvsql.so";
            case "macos" -> "libvsql.dylib";
            default -> "libvsql.dll";
        };
        byte[] library;
        try (InputStream in = Native.class.getResourceAsStream("native/" + platform + "/" + file)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("vsql: there's no libvsql for " + platform + " in the jar");
            }
            library = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // The file is named by the library's checksum, so it's only written
        // once (and every JVM that uses the same library loads the same file).
        // An existing file is only used if it has the same checksum.
        String checksum = sha256(library);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "vsql-" + checksum.substring(0, 16));
        Path path = dir.resolve(file);
        try {
            if (Files.isRegularFile(path) && sha256(Files.readAllBytes(path)).equals(checksum)) {
                return path;
            }
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "libvsql", ".tmp");
            Files.write(tmp, library);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Another JVM wrote it first (and on Windows, it can't be
                // replaced while it's loaded).
                Files.deleteIfExists(tmp);
                if (!Files.isRegularFile(path) || !sha256(Files.readAllBytes(path)).equals(checksum)) {
                    throw e;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("vsql: can't write libvsql to " + path, e);
        }

        return path;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Throws the error for a return code (SQL_NO_DATA isn't one). */
    private static void check(short rc, short handleType, int handle) throws SQLException {
        switch (rc) {
            case SQL_SUCCESS, SQL_SUCCESS_WITH_INFO, SQL_NO_DATA -> {
                return;
            }
            case SQL_INVALID_HANDLE -> throw Errors.sql("HY000", "invalid handle");
            default -> throw diagnostics(handleType, handle);
        }
    }

    /** The first diagnostic record of the last routine called with a handle. */
    private static SQLException diagnostics(short handleType, int handle) {
        int size = 512;
        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqlstate = arena.allocate(6);
                MemorySegment message = arena.allocate(size);
                MemorySegment length = arena.allocate(JAVA_SHORT);
                short rc = (short) GET_DIAG_REC.invokeExact(handleType, handle, (short) 1, sqlstate,
                        MemorySegment.NULL, message, (short) size, length);
                if (rc != SQL_SUCCESS && rc != SQL_SUCCESS_WITH_INFO) {
                    return Errors.sql("HY000", "unknown error");
                }

                // The message was truncated.
                int n = length.get(JAVA_SHORT, 0);
                if (n >= size) {
                    size = n + 1;
                    continue;
                }

                return Errors.sql(string(sqlstate, 5), string(message, n));
            } catch (Throwable t) {
                return Errors.sql("HY000", "can't read the error: " + t);
            }
        }
    }

    private static String string(MemorySegment segment, long length) {
        return new String(segment.asSlice(0, length).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static MemorySegment utf8(Arena arena, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return arena.allocateFrom(JAVA_BYTE, bytes.length == 0 ? new byte[1] : bytes);
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Wraps a Throwable from MethodHandle.invokeExact (which can't happen). */
    private static IllegalStateException unexpected(Throwable t) {
        return new IllegalStateException("vsql: calling libvsql failed", t);
    }

    static int allocEnv() throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment env = arena.allocate(JAVA_INT);
            if ((short) ALLOC_HANDLE.invokeExact(SQL_HANDLE_ENV, 0, env) != SQL_SUCCESS) {
                throw Errors.sql("HY001", "can't allocate an environment");
            }
            return env.get(JAVA_INT, 0);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Allocates a connection (in env) or a statement (in a connection). */
    static int allocHandle(short handleType, short inputType, int input) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(JAVA_INT);
            check((short) ALLOC_HANDLE.invokeExact(handleType, input, output), inputType, input);
            return output.get(JAVA_INT, 0);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void freeHandle(short handleType, int handle) throws SQLException {
        try {
            check((short) FREE_HANDLE.invokeExact(handleType, handle), handleType, handle);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void connect(int dbc, String name) throws SQLException {
        int length = utf8Length(name);
        if (length > Short.MAX_VALUE) {
            throw Errors.sql("HY090", "the database name is too long");
        }
        try (Arena arena = Arena.ofConfined()) {
            check((short) CONNECT.invokeExact(dbc, utf8(arena, name), (short) length,
                    MemorySegment.NULL, (short) 0, MemorySegment.NULL, (short) 0), SQL_HANDLE_DBC, dbc);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void disconnect(int dbc) throws SQLException {
        try {
            check((short) DISCONNECT.invokeExact(dbc), SQL_HANDLE_DBC, dbc);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void setAutocommit(int dbc, boolean on) throws SQLException {
        // invokeExact needs the exact types of its arguments, which a
        // conditional expression doesn't have, so it's a variable.
        MemorySegment value = on ? AUTOCOMMIT_ON : AUTOCOMMIT_OFF;
        try {
            check((short) SET_CONNECT_ATTR.invokeExact(dbc, SQL_ATTR_AUTOCOMMIT, value, 0), SQL_HANDLE_DBC, dbc);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void startTran(int dbc, int accessMode, int isolationLevel) throws SQLException {
        try {
            check((short) START_TRAN.invokeExact(SQL_HANDLE_DBC, dbc, accessMode, isolationLevel),
                    SQL_HANDLE_DBC, dbc);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void endTran(int dbc, boolean commit) throws SQLException {
        short completion = commit ? SQL_COMMIT : SQL_ROLLBACK;
        try {
            check((short) END_TRAN.invokeExact(SQL_HANDLE_DBC, dbc, completion), SQL_HANDLE_DBC, dbc);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void execDirect(int stmt, String sql) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            check((short) EXEC_DIRECT.invokeExact(stmt, utf8(arena, sql), utf8Length(sql)),
                    SQL_HANDLE_STMT, stmt);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static void prepare(int stmt, String sql) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            check((short) PREPARE.invokeExact(stmt, utf8(arena, sql), utf8Length(sql)), SQL_HANDLE_STMT, stmt);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Executes the prepared statement with the parameters, which are bound for
     * this execution only: their memory is freed when SQLExecute returns, so
     * they're unbound so that libvsql never reads them again.
     */
    static void execute(int stmt, Param[] params) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            try {
                MemorySegment inds = arena.allocate(JAVA_INT, Math.max(params.length, 1));
                for (int i = 0; i < params.length; i++) {
                    Param p = params[i];
                    MemorySegment ind = inds.asSlice((long) i * JAVA_INT.byteSize(), JAVA_INT.byteSize());
                    MemorySegment value;
                    int bufferLength;
                    switch (p.value()) {
                        case null -> {
                            ind.set(JAVA_INT, 0, SQL_NULL_DATA);
                            value = MemorySegment.NULL;
                            bufferLength = 0;
                        }
                        case Long l -> {
                            value = arena.allocateFrom(JAVA_LONG, l);
                            bufferLength = 8;
                        }
                        case Double d -> {
                            value = arena.allocateFrom(JAVA_DOUBLE, d);
                            bufferLength = 8;
                        }
                        case String s -> {
                            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                            value = arena.allocateFrom(JAVA_BYTE, bytes.length == 0 ? new byte[1] : bytes);
                            bufferLength = bytes.length;
                            ind.set(JAVA_INT, 0, bytes.length);
                        }
                        default -> throw new IllegalArgumentException("vsql: parameter " + p.value());
                    }
                    check((short) BIND_PARAMETER.invokeExact(stmt, (short) (i + 1), SQL_PARAM_MODE_IN,
                            p.valueType(), p.parameterType(), p.columnSize(), p.decimalDigits(), value,
                            bufferLength, ind), SQL_HANDLE_STMT, stmt);
                }
                check((short) EXECUTE.invokeExact(stmt), SQL_HANDLE_STMT, stmt);
            } finally {
                if (params.length > 0) {
                    short rc = (short) FREE_STMT.invokeExact(stmt, SQL_RESET_PARAMS);
                }
            }
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static long rowCount(int stmt) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment n = arena.allocate(JAVA_INT);
            check((short) ROW_COUNT.invokeExact(stmt, n), SQL_HANDLE_STMT, stmt);
            return n.get(JAVA_INT, 0);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    static int numResultCols(int stmt) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment n = arena.allocate(JAVA_SHORT);
            check((short) NUM_RESULT_COLS.invokeExact(stmt, n), SQL_HANDLE_STMT, stmt);
            return n.get(JAVA_SHORT, 0);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** Describes a column (starting at 1). */
    static Column describeCol(int stmt, int number) throws SQLException {
        int size = 129;
        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment name = arena.allocate(size);
                MemorySegment nameLength = arena.allocate(JAVA_SHORT);
                MemorySegment dataType = arena.allocate(JAVA_SHORT);
                MemorySegment columnSize = arena.allocate(JAVA_INT);
                MemorySegment decimalDigits = arena.allocate(JAVA_SHORT);
                MemorySegment nullable = arena.allocate(JAVA_SHORT);
                check((short) DESCRIBE_COL.invokeExact(stmt, (short) number, name, (short) size, nameLength,
                        dataType, columnSize, decimalDigits, nullable), SQL_HANDLE_STMT, stmt);

                // The name was truncated.
                int n = nameLength.get(JAVA_SHORT, 0);
                if (n >= size) {
                    size = n + 1;
                    continue;
                }

                return new Column(string(name, n), dataType.get(JAVA_SHORT, 0), columnSize.get(JAVA_INT, 0),
                        decimalDigits.get(JAVA_SHORT, 0), nullable.get(JAVA_SHORT, 0) != SQL_NO_NULLS);
            } catch (SQLException e) {
                throw e;
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    static void closeCursor(int stmt) throws SQLException {
        try {
            check((short) CLOSE_CURSOR.invokeExact(stmt), SQL_HANDLE_STMT, stmt);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Fetches the next row, it's null after the last row. Each column is read
     * as its kind (see Column.kind): a Long, Double, Float or String, or null
     * for a NULL.
     */
    static Object[] fetchRow(int stmt, Column[] columns) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            short rc = (short) FETCH.invokeExact(stmt);
            if (rc == SQL_NO_DATA) {
                return null;
            }
            check(rc, SQL_HANDLE_STMT, stmt);

            Object[] row = new Object[columns.length];
            MemorySegment ind = arena.allocate(JAVA_INT);
            MemorySegment number = arena.allocate(JAVA_LONG);
            for (int i = 0; i < columns.length; i++) {
                short n = (short) (i + 1);
                switch (columns[i].kind()) {
                    case INTEGER -> {
                        check((short) GET_DATA.invokeExact(stmt, n, SQL_BIGINT, number, 8, ind), SQL_HANDLE_STMT, stmt);
                        row[i] = ind.get(JAVA_INT, 0) == SQL_NULL_DATA ? null : number.get(JAVA_LONG, 0);
                    }
                    case DOUBLE -> {
                        check((short) GET_DATA.invokeExact(stmt, n, SQL_DOUBLE, number, 8, ind), SQL_HANDLE_STMT, stmt);
                        row[i] = ind.get(JAVA_INT, 0) == SQL_NULL_DATA ? null : number.get(JAVA_DOUBLE, 0);
                    }
                    case REAL -> {
                        check((short) GET_DATA.invokeExact(stmt, n, SQL_REAL, number, 4, ind), SQL_HANDLE_STMT, stmt);
                        row[i] = ind.get(JAVA_INT, 0) == SQL_NULL_DATA ? null : number.get(JAVA_FLOAT, 0);
                    }
                    case TEXT -> row[i] = getString(arena, stmt, n, ind);
                }
            }

            return row;
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Reads a column as SQL_CHAR, it's null for a NULL. A string that doesn't
     * fit in the buffer is read in pieces, the rest into a buffer that's the
     * size of the rest of it.
     */
    private static String getString(Arena arena, int stmt, short n, MemorySegment ind) throws Throwable {
        int size = 256;
        MemorySegment buf = arena.allocate(size);
        java.io.ByteArrayOutputStream s = null;
        while (true) {
            short rc = (short) GET_DATA.invokeExact(stmt, n, SQL_CHAR, buf, size, ind);
            check(rc, SQL_HANDLE_STMT, stmt);
            int length = ind.get(JAVA_INT, 0);
            if (length == SQL_NULL_DATA) {
                return null;
            }

            // The rest of the string (length) didn't fit, so the buffer has a
            // null terminated piece of it.
            if (rc == SQL_SUCCESS_WITH_INFO && length >= size) {
                byte[] piece = buf.getString(0, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1);
                if (s == null) {
                    s = new java.io.ByteArrayOutputStream(length);
                }
                s.write(piece);
                size = length - piece.length + 1;
                buf = arena.allocate(size);
                continue;
            }

            byte[] last = buf.asSlice(0, length).toArray(JAVA_BYTE);
            if (s == null) {
                return new String(last, StandardCharsets.UTF_8);
            }
            s.write(last);
            return s.toString(StandardCharsets.UTF_8);
        }
    }
}

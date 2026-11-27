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
import java.nio.file.Path;
import java.sql.SQLException;
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
 * scripts/build-libvsql.sh), and it's copied to a file in the temporary folder
 * to be loaded (see LibraryFile). The system property vsql.library.path is the
 * path of another libvsql to use instead.
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
    static final int SQL_NTS = -3;

    static final short SQL_HANDLE_ENV = 1;
    static final short SQL_HANDLE_DBC = 2;
    static final short SQL_HANDLE_STMT = 3;
    static final short SQL_HANDLE_DESC = 4;

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
    static final short SQL_DECFLOAT = 26;
    static final short SQL_BLOB = 30;
    static final short SQL_CLOB = 40;
    static final short SQL_BINARY = 60;
    static final short SQL_VARBINARY = 61;

    // The SQL_DESC_TYPE of a datetime or interval (its concise code is 90 or
    // 100 plus its SQL_DESC_DATETIME_INTERVAL_CODE).
    static final short SQL_DATETIME = 9;
    static final short SQL_INTERVAL = 10;
    static final short SQL_INTERVAL_YEAR = 101;
    static final short SQL_INTERVAL_MONTH = 102;
    static final short SQL_INTERVAL_DAY = 103;
    static final short SQL_INTERVAL_HOUR = 104;
    static final short SQL_INTERVAL_MINUTE = 105;
    static final short SQL_INTERVAL_SECOND = 106;
    static final short SQL_INTERVAL_YEAR_TO_MONTH = 107;
    static final short SQL_INTERVAL_DAY_TO_HOUR = 108;
    static final short SQL_INTERVAL_DAY_TO_MINUTE = 109;
    static final short SQL_INTERVAL_DAY_TO_SECOND = 110;
    static final short SQL_INTERVAL_HOUR_TO_MINUTE = 111;
    static final short SQL_INTERVAL_HOUR_TO_SECOND = 112;
    static final short SQL_INTERVAL_MINUTE_TO_SECOND = 113;
    static final short SQL_ALL_TYPES = 0;

    static final int SQL_ATTR_AUTOCOMMIT = 102;
    static final int SQL_AUTOCOMMIT_OFF = 0;
    static final int SQL_AUTOCOMMIT_ON = 1;
    static final short SQL_COMMIT = 0;
    static final short SQL_ROLLBACK = 1;
    static final short SQL_SAVEPOINT_NAME_ROLLBACK = 2;
    static final short SQL_SAVEPOINT_NAME_RELEASE = 4;
    static final int SQL_ATTR_SAVEPOINT_NAME = 10027;

    static final int SQL_TRANSACTION_READ_ONLY = 1;
    static final int SQL_TRANSACTION_READ_WRITE = 2;
    static final int SQL_TRANSACTION_READ_UNCOMMITTED = 1;
    static final int SQL_TRANSACTION_READ_COMMITTED = 2;
    static final int SQL_TRANSACTION_REPEATABLE_READ = 4;
    static final int SQL_TRANSACTION_SERIALIZABLE = 8;

    static final short SQL_PARAM_MODE_IN = 1;
    static final short SQL_RESET_PARAMS = 3;
    static final short SQL_NO_NULLS = 0;

    static final short SQL_DIAG_MORE = 13;
    static final short SQL_DIAG_CONSTRAINT_CATALOG = 15;
    static final short SQL_DIAG_CURSOR_NAME = 22;

    static final int SQL_ATTR_IMP_PARAM_DESC = 10013;
    static final short SQL_DESC_COUNT = 1001;
    static final short SQL_DESC_LENGTH = 1003;
    static final short SQL_DESC_DATETIME_INTERVAL_PRECISION = 26;

    static final short SQL_MAXIMUM_DRIVER_CONNECTIONS = 0;
    static final short SQL_MAXIMUM_CONCURRENT_ACTIVITIES = 1;
    static final short SQL_SEARCH_PATTERN_ESCAPE = 14;
    static final short SQL_DBMS_VERSION = 18;
    static final short SQL_MAXIMUM_COLUMN_NAME_LENGTH = 30;
    static final short SQL_MAXIMUM_CURSOR_NAME_LENGTH = 31;
    static final short SQL_MAXIMUM_SCHEMA_NAME_LENGTH = 32;
    static final short SQL_MAXIMUM_CATALOG_NAME_LENGTH = 34;
    static final short SQL_MAXIMUM_TABLE_NAME_LENGTH = 35;
    static final short SQL_TRANSACTION_CAPABLE = 46;
    static final short SQL_INTEGRITY = 73;
    static final short SQL_NULL_COLLATION = 85;
    static final short SQL_ALTER_TABLE = 86;
    static final short SQL_SPECIAL_CHARACTERS = 94;
    static final short SQL_MAXIMUM_COLUMNS_IN_GROUP_BY = 97;
    static final short SQL_MAXIMUM_COLUMNS_IN_ORDER_BY = 99;
    static final short SQL_MAXIMUM_COLUMNS_IN_SELECT = 100;
    static final short SQL_MAXIMUM_COLUMNS_IN_TABLE = 101;
    static final short SQL_MAXIMUM_TABLES_IN_SELECT = 106;
    static final short SQL_MAXIMUM_USER_NAME_LENGTH = 107;
    static final short SQL_MAXIMUM_IDENTIFIER_LENGTH = 10005;
    static final short SQL_MAXIMUM_STMT_OCTETS = 20000;
    static final int SQL_AT_ADD_COLUMN = 0x00000001;
    static final int SQL_AT_DROP_COLUMN = 0x00000002;
    static final short SQL_NC_HIGH = 1;
    static final short SQL_NC_LOW = 2;
    static final short SQL_TC_DML = 1;
    static final short SQL_TC_ALL = 2;
    static final short SQL_TC_DDL_COMMIT = 3;
    static final short SQL_TC_DDL_IGNORE = 4;
    static final short SQL_VSQL_BUILD_VERSION = 11000;
    static final short SQL_VSQL_FILE_READ_VERSION = 11008;
    static final short SQL_VSQL_FILE_WRITE_VERSION = 11009;

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
    private static final MethodHandle GET_DIAG_FIELD;
    private static final MethodHandle GET_INFO;
    private static final MethodHandle GET_FEATURE_INFO;
    private static final MethodHandle GET_STMT_ATTR;
    private static final MethodHandle GET_DESC_FIELD;
    private static final MethodHandle GET_DESC_REC;
    private static final MethodHandle GET_TYPE_INFO;
    private static final MethodHandle SET_CURSOR_NAME;
    private static final MethodHandle GET_CURSOR_NAME;

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
        GET_DIAG_FIELD = handle(linker, lib, "SQLGetDiagField", JAVA_SHORT, JAVA_INT, JAVA_SHORT,
                JAVA_SHORT, ADDRESS, JAVA_SHORT, ADDRESS);
        GET_INFO = handle(linker, lib, "SQLGetInfo", JAVA_INT, JAVA_SHORT, ADDRESS, JAVA_SHORT, ADDRESS);
        GET_FEATURE_INFO = handle(linker, lib, "SQLGetFeatureInfo", JAVA_INT, ADDRESS, JAVA_SHORT, ADDRESS,
                JAVA_SHORT, ADDRESS, JAVA_SHORT, ADDRESS);
        GET_STMT_ATTR = handle(linker, lib, "SQLGetStmtAttr", JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
        GET_DESC_FIELD = handle(linker, lib, "SQLGetDescField", JAVA_INT, JAVA_SHORT, JAVA_SHORT,
                ADDRESS, JAVA_INT, ADDRESS);
        GET_DESC_REC = handle(linker, lib, "SQLGetDescRec", JAVA_INT, JAVA_SHORT, ADDRESS, JAVA_SHORT,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        GET_TYPE_INFO = handle(linker, lib, "SQLGetTypeInfo", JAVA_INT, JAVA_SHORT);
        SET_CURSOR_NAME = handle(linker, lib, "SQLSetCursorName", JAVA_INT, ADDRESS, JAVA_SHORT);
        GET_CURSOR_NAME = handle(linker, lib, "SQLGetCursorName", JAVA_INT, ADDRESS, JAVA_SHORT, ADDRESS);

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

        return LibraryFile.extract(Path.of(System.getProperty("java.io.tmpdir")), file, library);
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

    /**
     * The diagnostic records of the last routine called with a handle, as an
     * SQLException for the first record, with the rest chained to it (see
     * SQLException.getNextException).
     */
    private static SQLException diagnostics(short handleType, int handle) {
        boolean more = false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment n = arena.allocate(JAVA_INT);
            short rc = (short) GET_DIAG_FIELD.invokeExact(handleType, handle, (short) 0, SQL_DIAG_MORE, n,
                    (short) 0, MemorySegment.NULL);
            more = rc == SQL_SUCCESS && n.get(JAVA_INT, 0) != 0;
        } catch (Throwable t) {
            // It's only a hint.
        }

        SQLException first = null;
        for (short record = 1; ; record++) {
            SQLException e = diagnostic(handleType, handle, record, more);
            if (e == null) {
                break;
            }
            if (first == null) {
                first = e;
            } else {
                first.setNextException(e);
            }
        }

        return first == null ? Errors.sql("HY000", "unknown error") : first;
    }

    /** A diagnostic record, or null if there isn't one. */
    private static SQLException diagnostic(short handleType, int handle, short record, boolean more) {
        int size = 512;
        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqlstate = arena.allocate(6);
                MemorySegment message = arena.allocate(size);
                MemorySegment length = arena.allocate(JAVA_SHORT);
                short rc = (short) GET_DIAG_REC.invokeExact(handleType, handle, record, sqlstate,
                        MemorySegment.NULL, message, (short) size, length);
                if (rc != SQL_SUCCESS && rc != SQL_SUCCESS_WITH_INFO) {
                    return null;
                }

                // The message was truncated.
                int n = length.get(JAVA_SHORT, 0);
                if (n >= size) {
                    size = n + 1;
                    continue;
                }

                // SQL_DIAG_CONSTRAINT_CATALOG to SQL_DIAG_CURSOR_NAME.
                String[] names = new String[SQL_DIAG_CURSOR_NAME - SQL_DIAG_CONSTRAINT_CATALOG + 1];
                for (int i = 0; i < names.length; i++) {
                    names[i] = diagString(handleType, handle, record, (short) (SQL_DIAG_CONSTRAINT_CATALOG + i));
                }
                VsqlDiagnostics d = new VsqlDiagnostics(names[0], names[1], names[2], names[3], names[4],
                        names[5], names[6], names[7], more);

                return Errors.sql(string(sqlstate, 5), string(message, n), d);
            } catch (Throwable t) {
                return Errors.sql("HY000", "can't read the error: " + t);
            }
        }
    }

    /** A string field of a diagnostic record, or null if it's empty. */
    private static String diagString(short handleType, int handle, short record, short field) throws Throwable {
        int size = 129;
        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment value = arena.allocate(size);
                MemorySegment length = arena.allocate(JAVA_SHORT);
                short rc = (short) GET_DIAG_FIELD.invokeExact(handleType, handle, record, field, value,
                        (short) size, length);
                if (rc != SQL_SUCCESS && rc != SQL_SUCCESS_WITH_INFO) {
                    return null;
                }
                int n = length.get(JAVA_SHORT, 0);
                if (n >= size) {
                    size = n + 1;
                    continue;
                }
                return n == 0 ? null : string(value, n);
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

    /**
     * Rolls back to (SQL_SAVEPOINT_NAME_ROLLBACK) or releases
     * (SQL_SAVEPOINT_NAME_RELEASE) a savepoint, which is named by
     * SQL_ATTR_SAVEPOINT_NAME.
     */
    static void endSavepoint(int dbc, String name, short completion) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            check((short) SET_CONNECT_ATTR.invokeExact(dbc, SQL_ATTR_SAVEPOINT_NAME, utf8(arena, name),
                    utf8Length(name)), SQL_HANDLE_DBC, dbc);
            check((short) END_TRAN.invokeExact(SQL_HANDLE_DBC, dbc, completion), SQL_HANDLE_DBC, dbc);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** A string from SQLGetInfo. */
    static String getInfoString(int dbc, short infoType) throws SQLException {
        int size = 129;
        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment value = arena.allocate(size);
                MemorySegment length = arena.allocate(JAVA_SHORT);
                check((short) GET_INFO.invokeExact(dbc, infoType, value, (short) size, length), SQL_HANDLE_DBC, dbc);
                int n = length.get(JAVA_SHORT, 0);
                if (n >= size) {
                    size = n + 1;
                    continue;
                }
                return string(value, n);
            } catch (SQLException e) {
                throw e;
            } catch (Throwable t) {
                throw unexpected(t);
            }
        }
    }

    /** An SQLSMALLINT from SQLGetInfo. */
    static short getInfoShort(int dbc, short infoType) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_SHORT);
            check((short) GET_INFO.invokeExact(dbc, infoType, value, (short) 0, MemorySegment.NULL),
                    SQL_HANDLE_DBC, dbc);
            return value.get(JAVA_SHORT, 0);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /** An SQLINTEGER from SQLGetInfo. */
    static int getInfoInt(int dbc, short infoType) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            check((short) GET_INFO.invokeExact(dbc, infoType, value, (short) 0, MemorySegment.NULL),
                    SQL_HANDLE_DBC, dbc);
            return value.get(JAVA_INT, 0);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Whether vsql supports a feature (such as "F401"), or a subfeature (such
     * as "E061" and "05") if subFeatureId isn't null, from SQLGetFeatureInfo.
     * An unknown feature is SQLSTATE HY024.
     */
    static boolean getFeatureInfo(int dbc, String featureId, String subFeatureId) throws SQLException {
        String type = subFeatureId == null ? "FEATURE" : "SUBFEATURE";
        short nts = SQL_NTS;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sub = subFeatureId == null ? MemorySegment.NULL : arena.allocateFrom(subFeatureId);
            MemorySegment supported = arena.allocate(JAVA_SHORT);
            check((short) GET_FEATURE_INFO.invokeExact(dbc, arena.allocateFrom(type), nts,
                    arena.allocateFrom(featureId), nts, sub, nts, supported), SQL_HANDLE_DBC, dbc);
            return supported.get(JAVA_SHORT, 0) != 0;
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Describes the dynamic parameters of a prepared statement, from its IPD.
     * The type of a datetime or interval is its concise code (such as
     * SQL_TYPE_DATE or SQL_INTERVAL_DAY), like SQLDescribeCol.
     */
    static Column[] describeParams(int stmt) throws SQLException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(JAVA_INT);
            check((short) GET_STMT_ATTR.invokeExact(stmt, SQL_ATTR_IMP_PARAM_DESC, desc, 0, MemorySegment.NULL),
                    SQL_HANDLE_STMT, stmt);
            int ipd = desc.get(JAVA_INT, 0);
            MemorySegment count = arena.allocate(JAVA_SHORT);
            check((short) GET_DESC_FIELD.invokeExact(ipd, (short) 0, SQL_DESC_COUNT, count, 0, MemorySegment.NULL),
                    SQL_HANDLE_DESC, ipd);

            Column[] params = new Column[count.get(JAVA_SHORT, 0)];
            MemorySegment type = arena.allocate(JAVA_SHORT);
            MemorySegment subType = arena.allocate(JAVA_SHORT);
            MemorySegment octetLength = arena.allocate(JAVA_INT);
            MemorySegment precision = arena.allocate(JAVA_SHORT);
            MemorySegment scale = arena.allocate(JAVA_SHORT);
            MemorySegment nullable = arena.allocate(JAVA_SHORT);
            MemorySegment length = arena.allocate(JAVA_INT);
            for (short i = 1; i <= params.length; i++) {
                check((short) GET_DESC_REC.invokeExact(ipd, i, MemorySegment.NULL, (short) 0, MemorySegment.NULL,
                        type, subType, octetLength, precision, scale, nullable), SQL_HANDLE_DESC, ipd);
                check((short) GET_DESC_FIELD.invokeExact(ipd, i, SQL_DESC_LENGTH, length, 0, MemorySegment.NULL),
                        SQL_HANDLE_DESC, ipd);
                short t = type.get(JAVA_SHORT, 0);
                short concise = switch (t) {
                    case SQL_DATETIME -> (short) (90 + subType.get(JAVA_SHORT, 0));
                    case SQL_INTERVAL -> (short) (100 + subType.get(JAVA_SHORT, 0));
                    default -> t;
                };
                params[i - 1] = Column.ofDescriptor(concise, octetLength.get(JAVA_INT, 0), length.get(JAVA_INT, 0),
                        precision.get(JAVA_SHORT, 0), scale.get(JAVA_SHORT, 0), nullable.get(JAVA_SHORT, 0) != SQL_NO_NULLS);
            }
            return params;
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Opens a cursor on stmt with SQLGetTypeInfo for every data type, see
     * VsqlDatabaseMetaData.getTypeInfo.
     */
    static void getTypeInfo(int stmt) throws SQLException {
        try {
            check((short) GET_TYPE_INFO.invokeExact(stmt, SQL_ALL_TYPES), SQL_HANDLE_STMT, stmt);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * Names the cursor of a statement with SQLSetCursorName. The name is an
     * identifier (so c and C are the same cursor).
     */
    static void setCursorName(int stmt, String name) throws SQLException {
        int length = name == null ? 0 : utf8Length(name);
        if (length > Short.MAX_VALUE) {
            throw Errors.sql("HY090", "the cursor name is too long");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = name == null ? MemorySegment.NULL : utf8(arena, name);
            check((short) SET_CURSOR_NAME.invokeExact(stmt, value, (short) length), SQL_HANDLE_STMT, stmt);
        } catch (SQLException e) {
            throw e;
        } catch (Throwable t) {
            throw unexpected(t);
        }
    }

    /**
     * The name of the cursor of a statement, from SQLGetCursorName. A
     * statement that wasn't given one has a unique name that starts with
     * "SQL_CUR".
     */
    static String getCursorName(int stmt) throws SQLException {
        int size = 129;
        while (true) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment value = arena.allocate(size);
                MemorySegment length = arena.allocate(JAVA_SHORT);
                check((short) GET_CURSOR_NAME.invokeExact(stmt, value, (short) size, length), SQL_HANDLE_STMT, stmt);
                int n = length.get(JAVA_SHORT, 0);
                if (n >= size) {
                    size = n + 1;
                    continue;
                }
                return string(value, n);
            } catch (SQLException e) {
                throw e;
            } catch (Throwable t) {
                throw unexpected(t);
            }
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
                            // A length must be greater than zero, an empty
                            // string is given null terminated.
                            ind.set(JAVA_INT, 0, bytes.length == 0 ? SQL_NTS : bytes.length);
                        }
                        case byte[] b -> {
                            // A binary string can be empty (unlike a
                            // character string).
                            value = arena.allocateFrom(JAVA_BYTE, b.length == 0 ? new byte[1] : b);
                            bufferLength = b.length;
                            ind.set(JAVA_INT, 0, b.length);
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
     * Fetcher reads the rows of a cursor. Its buffers are allocated once and
     * reused for every row (rather than an Arena for each row). A larger
     * buffer for a long value (up to KEEP octets) is kept, so that the next
     * value that long is read with one SQLGetData.
     *
     * <p>The memory is freed by the garbage collector when the Fetcher (and so
     * its result set) is unreachable: a result set can be used by more than
     * one thread (one at a time), so it can't be a confined Arena, and nothing
     * can use the memory after it's freed. libvsql only writes to the buffers
     * during SQLGetData, it doesn't keep them.
     */
    static final class Fetcher {
        private static final int SIZE = 256;
        private static final int KEEP = 1 << 20;

        private final int stmt;
        private final Column[] columns;
        private final MemorySegment ind;
        private final MemorySegment number;
        // At least SIZE octets (and at most KEEP), it's replaced by a larger
        // one when a value needs it.
        private MemorySegment buf;

        Fetcher(int stmt, Column[] columns) {
            this.stmt = stmt;
            this.columns = columns;
            Arena arena = Arena.ofAuto();
            ind = arena.allocate(JAVA_INT);
            number = arena.allocate(JAVA_LONG);
            buf = arena.allocate(SIZE);
        }

        /**
         * A buffer of at least size octets. One that's larger than KEEP is
         * only used for one value, so a result set doesn't hold on to the
         * memory of its longest value.
         */
        private MemorySegment buffer(int size) {
            if (buf.byteSize() >= size) {
                return buf;
            }
            MemorySegment b = Arena.ofAuto().allocate(size);
            if (size <= KEEP) {
                buf = b;
            }
            return b;
        }

        /**
         * Fetches the next row, it's null after the last row. Each column is
         * read as its kind (see Column.kind): a Long, Double, Float, String or
         * byte[], or null for a NULL.
         */
        Object[] next() throws SQLException {
            try {
                short rc = (short) FETCH.invokeExact(stmt);
                if (rc == SQL_NO_DATA) {
                    return null;
                }
                check(rc, SQL_HANDLE_STMT, stmt);

                Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    short n = (short) (i + 1);
                    switch (columns[i].kind()) {
                        case INTEGER -> {
                            check((short) GET_DATA.invokeExact(stmt, n, SQL_BIGINT, number, 8, ind),
                                    SQL_HANDLE_STMT, stmt);
                            row[i] = ind.get(JAVA_INT, 0) == SQL_NULL_DATA ? null : number.get(JAVA_LONG, 0);
                        }
                        case DOUBLE -> {
                            check((short) GET_DATA.invokeExact(stmt, n, SQL_DOUBLE, number, 8, ind),
                                    SQL_HANDLE_STMT, stmt);
                            row[i] = ind.get(JAVA_INT, 0) == SQL_NULL_DATA ? null : number.get(JAVA_DOUBLE, 0);
                        }
                        case REAL -> {
                            check((short) GET_DATA.invokeExact(stmt, n, SQL_REAL, number, 4, ind),
                                    SQL_HANDLE_STMT, stmt);
                            row[i] = ind.get(JAVA_INT, 0) == SQL_NULL_DATA ? null : number.get(JAVA_FLOAT, 0);
                        }
                        case TEXT -> row[i] = getString(n);
                        case BINARY -> row[i] = getBytes(n);
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
         * Reads a column as SQL_CHAR, it's null for a NULL. A string that
         * doesn't fit in the buffer is read in pieces, the rest into a buffer
         * that's at least the size of the rest of it.
         */
        private String getString(short n) throws Throwable {
            MemorySegment b = buf;
            int size = (int) b.byteSize();
            java.io.ByteArrayOutputStream s = null;
            while (true) {
                short rc = (short) GET_DATA.invokeExact(stmt, n, SQL_CHAR, b, size, ind);
                check(rc, SQL_HANDLE_STMT, stmt);
                int length = ind.get(JAVA_INT, 0);
                if (length == SQL_NULL_DATA) {
                    return null;
                }

                // The rest of the string (length) didn't fit, so the buffer
                // has a null terminated piece of it. The piece is always
                // size - 1 octets (the buffer can be larger): it isn't read up
                // to the first 0 octet, because the string can contain U+0000.
                // It can end in the middle of a UTF-8 character, so the octets
                // are decoded at the end.
                if (rc == SQL_SUCCESS_WITH_INFO && length >= size) {
                    byte[] piece = b.asSlice(0, size - 1).toArray(JAVA_BYTE);
                    if (s == null) {
                        s = new java.io.ByteArrayOutputStream(length);
                    }
                    s.write(piece);
                    size = length - piece.length + 1;
                    b = buffer(size);
                    continue;
                }

                byte[] last = b.asSlice(0, length).toArray(JAVA_BYTE);
                if (s == null) {
                    return new String(last, StandardCharsets.UTF_8);
                }
                s.write(last);
                return s.toString(StandardCharsets.UTF_8);
            }
        }

        /**
         * Reads a column as SQL_BINARY, it's null for a NULL. A value that
         * doesn't fit in the buffer is read in pieces (like getString, but
         * there's no null terminator).
         */
        private byte[] getBytes(short n) throws Throwable {
            MemorySegment b = buf;
            int size = (int) b.byteSize();
            java.io.ByteArrayOutputStream out = null;
            while (true) {
                short rc = (short) GET_DATA.invokeExact(stmt, n, SQL_BINARY, b, size, ind);
                check(rc, SQL_HANDLE_STMT, stmt);
                int length = ind.get(JAVA_INT, 0);
                if (length == SQL_NULL_DATA) {
                    return null;
                }

                // The rest of the value (length) didn't fit, so the first size
                // octets of the buffer are full.
                if (rc == SQL_SUCCESS_WITH_INFO && length > size) {
                    if (out == null) {
                        out = new java.io.ByteArrayOutputStream(length);
                    }
                    out.write(b.asSlice(0, size).toArray(JAVA_BYTE));
                    size = length - size;
                    b = buffer(size);
                    continue;
                }

                byte[] last = b.asSlice(0, Math.min(length, size)).toArray(JAVA_BYTE);
                if (out == null) {
                    return last;
                }
                out.write(last);
                return out.toByteArray();
            }
        }
    }
}

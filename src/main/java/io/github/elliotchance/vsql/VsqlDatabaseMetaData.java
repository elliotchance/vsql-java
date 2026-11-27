package io.github.elliotchance.vsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Information about vsql and the driver, see {@link Connection#getMetaData()}.
 * The methods that describe the database's tables (such as getTables) aren't
 * supported yet.
 */
public final class VsqlDatabaseMetaData extends UnsupportedDatabaseMetaData {
    private final VsqlConnection connection;

    VsqlDatabaseMetaData(VsqlConnection connection) {
        this.connection = connection;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public String getURL() {
        return connection.url();
    }

    @Override
    public String getUserName() {
        return null;
    }

    @Override
    public String getDatabaseProductName() {
        return "vsql";
    }

    private String info(short infoType) throws SQLException {
        synchronized (connection) {
            connection.check();
            return Native.getInfoString(connection.dbc(), infoType);
        }
    }

    /**
     * The vsql release that libvsql is from, such as "1.7.58". A libvsql
     * that isn't from a release is the version of the release before it.
     */
    @Override
    public String getDatabaseProductVersion() throws SQLException {
        String v = info(Native.SQL_VSQL_BUILD_VERSION);
        if (v.startsWith("v")) {
            return v.substring(1);
        }

        // SQL_DBMS_VERSION is "01.07.0058".
        String[] parts = info(Native.SQL_DBMS_VERSION).split("\\.");
        StringBuilder version = new StringBuilder();
        for (String part : parts) {
            version.append(version.isEmpty() ? "" : ".").append(Integer.parseInt(part));
        }
        return version.toString();
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return Version.part(getDatabaseProductVersion(), 0);
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return Version.part(getDatabaseProductVersion(), 1);
    }

    /**
     * The git commit of vsql that libvsql was built from, or "" if it isn't
     * from a release.
     */
    public String getBuildCommit() throws SQLException {
        return info((short) (Native.SQL_VSQL_BUILD_VERSION + 1));
    }

    /** The date that libvsql was built, such as "2026-11-26" (UTC). */
    public String getBuildDate() throws SQLException {
        return info((short) (Native.SQL_VSQL_BUILD_VERSION + 2));
    }

    /** The version of V that built libvsql, such as "0.5.2". */
    public String getVVersion() throws SQLException {
        return info((short) (Native.SQL_VSQL_BUILD_VERSION + 3));
    }

    /** The git commit of V that built libvsql. */
    public String getVCommit() throws SQLException {
        return info((short) (Native.SQL_VSQL_BUILD_VERSION + 4));
    }

    /** The C compiler that built libvsql, such as "clang". */
    public String getCCompiler() throws SQLException {
        return info((short) (Native.SQL_VSQL_BUILD_VERSION + 5));
    }

    /** The operating system that libvsql was built for, such as "macos". */
    public String getOperatingSystem() throws SQLException {
        return info((short) (Native.SQL_VSQL_BUILD_VERSION + 6));
    }

    /** The machine that libvsql was built for, such as "arm64". */
    public String getMachine() throws SQLException {
        return info((short) (Native.SQL_VSQL_BUILD_VERSION + 7));
    }

    /**
     * The file format version that's needed to read the database file, or 0
     * for an in-memory database.
     */
    public int getFileReadVersion() throws SQLException {
        synchronized (connection) {
            connection.check();
            return Native.getInfoShort(connection.dbc(), Native.SQL_VSQL_FILE_READ_VERSION);
        }
    }

    /**
     * The file format version that's needed to write to the database file, or
     * 0 for an in-memory database.
     */
    public int getFileWriteVersion() throws SQLException {
        synchronized (connection) {
            connection.check();
            return Native.getInfoShort(connection.dbc(), Native.SQL_VSQL_FILE_WRITE_VERSION);
        }
    }

    @Override
    public String getDriverName() {
        return "vsql";
    }

    @Override
    public String getDriverVersion() {
        return Version.DRIVER;
    }

    @Override
    public int getDriverMajorVersion() {
        return Version.part(Version.DRIVER, 0);
    }

    @Override
    public int getDriverMinorVersion() {
        return Version.part(Version.DRIVER, 1);
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 3;
    }

    @Override
    public int getSQLStateType() {
        return DatabaseMetaData.sqlStateSQL;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean supportsTransactions() {
        return true;
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    /**
     * Every level is supported, but vsql runs READ UNCOMMITTED as READ
     * COMMITTED and REPEATABLE READ as SERIALIZABLE.
     */
    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED,
                    Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE -> true;
            default -> false;
        };
    }

    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return true;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean usesLocalFiles() {
        return true;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    /**
     * A positioned DELETE ({@code WHERE CURRENT OF}) can use the cursor of a
     * result set, see Statement.setCursorName.
     */
    @Override
    public boolean supportsPositionedDelete() {
        return true;
    }

    /** See supportsPositionedDelete. */
    @Override
    public boolean supportsPositionedUpdate() {
        return true;
    }

    // The columns of getTypeInfo.
    private static final Column[] TYPE_INFO = {
        new Column("TYPE_NAME", Native.SQL_VARCHAR, 512, (short) 0, false),
        new Column("DATA_TYPE", Native.SQL_INTEGER, 0, (short) 0, false),
        new Column("PRECISION", Native.SQL_INTEGER, 0, (short) 0, true),
        new Column("LITERAL_PREFIX", Native.SQL_VARCHAR, 512, (short) 0, true),
        new Column("LITERAL_SUFFIX", Native.SQL_VARCHAR, 512, (short) 0, true),
        new Column("CREATE_PARAMS", Native.SQL_VARCHAR, 512, (short) 0, true),
        new Column("NULLABLE", Native.SQL_SMALLINT, 0, (short) 0, false),
        new Column("CASE_SENSITIVE", Native.SQL_BOOLEAN, 0, (short) 0, false),
        new Column("SEARCHABLE", Native.SQL_SMALLINT, 0, (short) 0, false),
        new Column("UNSIGNED_ATTRIBUTE", Native.SQL_BOOLEAN, 0, (short) 0, false),
        new Column("FIXED_PREC_SCALE", Native.SQL_BOOLEAN, 0, (short) 0, false),
        new Column("AUTO_INCREMENT", Native.SQL_BOOLEAN, 0, (short) 0, false),
        new Column("LOCAL_TYPE_NAME", Native.SQL_VARCHAR, 512, (short) 0, true),
        new Column("MINIMUM_SCALE", Native.SQL_SMALLINT, 0, (short) 0, true),
        new Column("MAXIMUM_SCALE", Native.SQL_SMALLINT, 0, (short) 0, true),
        new Column("SQL_DATA_TYPE", Native.SQL_INTEGER, 0, (short) 0, true),
        new Column("SQL_DATETIME_SUB", Native.SQL_INTEGER, 0, (short) 0, true),
        new Column("NUM_PREC_RADIX", Native.SQL_INTEGER, 0, (short) 0, true),
    };

    /**
     * The data types, from SQLGetTypeInfo. DATA_TYPE is the java.sql.Types
     * code (OTHER for an interval), and SQL_DATA_TYPE and SQL_DATETIME_SUB are
     * the SQL/CLI codes. They're ordered by DATA_TYPE.
     */
    @Override
    public ResultSet getTypeInfo() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        synchronized (connection) {
            connection.check();
            int stmt = Native.allocHandle(Native.SQL_HANDLE_STMT, Native.SQL_HANDLE_DBC, connection.dbc());
            try {
                Native.getTypeInfo(stmt);
                Column[] columns = new Column[Native.numResultCols(stmt)];
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = Native.describeCol(stmt, i + 1);
                }
                Native.Fetcher fetcher = new Native.Fetcher(stmt, columns);
                for (Object[] r = fetcher.next(); r != null; r = fetcher.next()) {
                    Column type = new Column("", (short) number(r[1]), 0, (short) 0, true);
                    rows.add(new Object[] {
                        r[0], (long) type.jdbcType(), r[2], r[3], r[4], r[5], r[6], bool(r[7]), r[8], bool(r[9]),
                        bool(r[10]), bool(r[11]), r[12], r[13], r[14], r[15], r[16], r[17],
                    });
                }
                Native.closeCursor(stmt);
            } finally {
                Native.freeHandle(Native.SQL_HANDLE_STMT, stmt);
            }
        }
        rows.sort(Comparator.comparingLong(r -> (long) r[1]));
        return new VsqlResultSet(TYPE_INFO, rows);
    }

    private static long number(Object v) {
        return v instanceof Long l ? l : Long.parseLong(v.toString().trim());
    }

    /** A number (1 or 0) or NULL as a BOOLEAN ("TRUE" or "FALSE"). */
    private static String bool(Object v) {
        return v != null && number(v) != 0 ? "TRUE" : "FALSE";
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("vsql: not a wrapper for " + iface.getName(), "HY000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}

package io.github.elliotchance.vsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Information about vsql and the driver, see {@link Connection#getMetaData()}.
 * The methods that describe the database's tables (such as getTables) aren't
 * supported yet.
 */
public final class VsqlDatabaseMetaData extends UnsupportedDatabaseMetaData {
    private final VsqlConnection connection;
    private final Map<String, Boolean> features = new HashMap<>();

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
        return type == ResultSet.TYPE_FORWARD_ONLY || type == ResultSet.TYPE_SCROLL_INSENSITIVE;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return supportsResultSetType(type) && concurrency == ResultSet.CONCUR_READ_ONLY;
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

    private short infoShort(short infoType) throws SQLException {
        synchronized (connection) {
            connection.check();
            return Native.getInfoShort(connection.dbc(), infoType);
        }
    }

    private int alterTable() throws SQLException {
        synchronized (connection) {
            connection.check();
            return Native.getInfoInt(connection.dbc(), Native.SQL_ALTER_TABLE);
        }
    }

    private short transactionCapable() throws SQLException {
        return infoShort(Native.SQL_TRANSACTION_CAPABLE);
    }

    private short nullCollation() throws SQLException {
        return infoShort(Native.SQL_NULL_COLLATION);
    }

    /**
     * Whether vsql supports a feature or subfeature of the SQL standard (see
     * https://vsql.readthedocs.io/en/latest/sql-compliance.html), from
     * SQLGetFeatureInfo. Each answer is kept, since it's a query of the
     * INFORMATION_SCHEMA.SQL_FEATURES view.
     */
    private boolean feature(String featureId, String subFeatureId) throws SQLException {
        String key = subFeatureId == null ? featureId : featureId + "-" + subFeatureId;
        synchronized (connection) {
            connection.check();
            Boolean supported = features.get(key);
            if (supported == null) {
                supported = Native.getFeatureInfo(connection.dbc(), featureId, subFeatureId);
                features.put(key, supported);
            }
            return supported;
        }
    }

    private boolean feature(String featureId) throws SQLException {
        return feature(featureId, null);
    }

    /** There are no procedures (or privileges). */
    @Override
    public boolean allProceduresAreCallable() {
        return true;
    }

    /** There are no privileges. */
    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return transactionCapable() == Native.SQL_TC_DDL_COMMIT;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return transactionCapable() == Native.SQL_TC_DDL_IGNORE;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    /** There's no maximum row size. */
    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public String getCatalogTerm() {
        return "catalog";
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return info(Native.SQL_SPECIAL_CHARACTERS);
    }

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_CATALOG_NAME_LENGTH);
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_COLUMN_NAME_LENGTH);
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_COLUMNS_IN_GROUP_BY);
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_COLUMNS_IN_ORDER_BY);
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_COLUMNS_IN_SELECT);
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_COLUMNS_IN_TABLE);
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_DRIVER_CONNECTIONS);
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_CURSOR_NAME_LENGTH);
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_IDENTIFIER_LENGTH);
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_SCHEMA_NAME_LENGTH);
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_STMT_OCTETS);
    }

    @Override
    public int getMaxStatements() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_CONCURRENT_ACTIVITIES);
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_TABLE_NAME_LENGTH);
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_TABLES_IN_SELECT);
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return infoShort(Native.SQL_MAXIMUM_USER_NAME_LENGTH);
    }

    /**
     * The Open Group CLI math functions that vsql has (with the same name and
     * arguments). There are no escapes, so they're called by their names.
     */
    @Override
    public String getNumericFunctions() {
        return "ABS,ACOS,ASIN,ATAN,ATAN2,CEILING,COS,DEGREES,EXP,FLOOR,LOG10,MOD,POWER,RADIANS,"
                + "ROUND,SIGN,SIN,SQRT,TAN";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    /**
     * The reserved words of vsql (those of SQL:2023, and a few of its own,
     * such as SHOW) that aren't reserved words in SQL:2003.
     */
    @Override
    public String getSQLKeywords() {
        return "ABSENT,ACOS,ANY_VALUE,ARRAY_AGG,ARRAY_MAX_CARDINALITY,ASIN,ATAN,"
                + "BEGIN_FRAME,BEGIN_PARTITION,BTRIM,CLASSIFIER,CONTAINS,COPY,COS,COSH,"
                + "CURRENT_CATALOG,CURRENT_ROW,CURRENT_SCHEMA,DECFLOAT,DEFINE,EMPTY,"
                + "END_FRAME,END_PARTITION,EQUALS,FIRST_VALUE,FRAME_ROW,GREATEST,GROUPS,"
                + "INITIAL,JSON,JSON_ARRAY,JSON_ARRAYAGG,JSON_EXISTS,JSON_OBJECT,"
                + "JSON_OBJECTAGG,JSON_QUERY,JSON_SCALAR,JSON_SERIALIZE,JSON_TABLE,"
                + "JSON_TABLE_PRIMITIVE,JSON_VALUE,LAG,LAST_VALUE,LEAD,LEAST,LIKE_REGEX,"
                + "LISTAGG,LOG,LOG10,LPAD,LTRIM,MATCHES,MATCH_NUMBER,MATCH_RECOGNIZE,"
                + "NTH_VALUE,NTILE,OCCURRENCES_REGEX,OFFSET,OMIT,ONE,PATTERN,PER,PERCENT,"
                + "PERIOD,PORTION,POSITION_REGEX,PRECEDES,PTF,RPAD,RTRIM,RUNNING,SEEK,"
                + "SHOW,SIN,SINH,SKIP,SUBSET,SUBSTRING_REGEX,SUCCEEDS,SYSTEM_TIME,TAN,"
                + "TANH,TRANSLATE_REGEX,TRIM_ARRAY,TRUNCATE,VALUE_OF,VARBINARY,"
                + "VERSIONING";
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return info(Native.SQL_SEARCH_PATTERN_ESCAPE);
    }

    /**
     * The Open Group CLI string functions that vsql has, see
     * getNumericFunctions.
     */
    @Override
    public String getStringFunctions() {
        return "CHAR_LENGTH,CHARACTER_LENGTH,CONCAT,LENGTH,LOWER,LTRIM,OCTET_LENGTH,POSITION,"
                + "REPEAT,REPLACE,RTRIM,SUBSTRING,TRIM,UPPER";
    }

    /** vsql has none of the Open Group CLI system functions. */
    @Override
    public String getSystemFunctions() {
        return "";
    }

    /**
     * The Open Group CLI date and time functions that vsql has, see
     * getNumericFunctions.
     */
    @Override
    public String getTimeDateFunctions() {
        return "CURRENT_DATE,CURRENT_TIME,CURRENT_TIMESTAMP,EXTRACT,NOW";
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return nullCollation() == Native.SQL_NC_HIGH;
    }

    /**
     * NULL is less than any other value (unless an ORDER BY has NULLS FIRST or
     * NULLS LAST).
     */
    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return nullCollation() == Native.SQL_NC_LOW;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return true;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return (alterTable() & Native.SQL_AT_ADD_COLUMN) != 0;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return (alterTable() & Native.SQL_AT_DROP_COLUMN) != 0;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return true;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return true;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return feature("E051", "05");
    }

    /** There are no escapes, so there's no CONVERT function (vsql has CAST). */
    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return feature("E061", "13");
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return transactionCapable() == Native.SQL_TC_ALL;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return transactionCapable() == Native.SQL_TC_DML;
    }

    /** A correlation name can be the same as the name of its table. */
    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return feature("E121", "03");
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return feature("F401");
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return feature("E051", "04");
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return feature("E051", "04");
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return "Y".equals(info(Native.SQL_INTEGRITY));
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return feature("E061", "05");
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return supportsOuterJoins();
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return true;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    /**
     * A result set is closed when its transaction ends, see
     * getResultSetHoldability.
     */
    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return feature("E121", "02");
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return feature("F041", "03") && feature("F041", "04");
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

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return true;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return true;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return true;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return true;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return feature("E061", "09");
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return feature("E061", "08");
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return feature("E061", "11");
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return feature("E061", "12");
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        return feature("E051", "08");
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return feature("E071", "01");
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return feature("E071", "02");
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    /** The database is one file. */
    @Override
    public boolean usesLocalFilePerTable() {
        return false;
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
                for (Object[] r = fetcher.next(null); r != null; r = fetcher.next(null)) {
                    Column type = new Column("", (short) number(r[1]), 0, (short) 0, true);
                    rows.add(new Object[] {
                        r[0], (long) type.jdbcType(), r[2], r[3], r[4], r[5], r[6], bool(r[7]), r[8], bool(r[9]),
                        bool(r[10]), bool(r[11]), r[12], r[13], r[14], r[15], r[16], r[17],
                    });
                }
                Native.closeCursor(stmt, null);
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
        throw Errors.sql("HY000", "vsql: not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}

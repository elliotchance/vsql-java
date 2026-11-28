package io.github.elliotchance.vsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Information about vsql and the driver, see {@link Connection#getMetaData()}.
 * The methods that describe the database's objects (such as getTables and
 * getColumns) use libvsql's catalog routines (such as SQLTables), and the ones
 * for the objects that vsql doesn't have (such as getProcedures) return a
 * result set without rows.
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

    /** Opens a cursor on a statement handle, see rows. */
    private interface Cursor {
        void open(int stmt) throws SQLException;
    }

    /** The rows of a cursor, and the position of each column by its name. */
    private record Rows(Map<String, Integer> columns, List<Object[]> rows) {
        Object get(Object[] row, String column) {
            return row[columns.get(column)];
        }
    }

    /**
     * Opens a cursor (such as with SQLTables) on a new statement handle, and
     * reads all of its rows.
     */
    private Rows rows(Cursor cursor) throws SQLException {
        synchronized (connection) {
            connection.check();
            int stmt = Native.allocHandle(Native.SQL_HANDLE_STMT, Native.SQL_HANDLE_DBC, connection.dbc());
            try {
                cursor.open(stmt);
                Column[] columns = new Column[Native.numResultCols(stmt)];
                Map<String, Integer> names = new HashMap<>();
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = Native.describeCol(stmt, i + 1);
                    names.put(columns[i].name(), i);
                }
                List<Object[]> rows = new ArrayList<>();
                Native.Fetcher fetcher = new Native.Fetcher(stmt, columns);
                for (Object[] r = fetcher.next(); r != null; r = fetcher.next()) {
                    rows.add(r);
                }
                Native.closeCursor(stmt);
                return new Rows(names, rows);
            } finally {
                Native.freeHandle(Native.SQL_HANDLE_STMT, stmt);
            }
        }
    }

    /** A result set without rows (for the objects that vsql doesn't have). */
    private ResultSet empty(Column[] columns) throws SQLException {
        synchronized (connection) {
            connection.check();
            return new VsqlResultSet(columns, List.of());
        }
    }

    /**
     * Whether a catalog, schema or name is a zero-length string, which JDBC
     * uses for an object without one (such as a table without a schema).
     * Every object of vsql has a catalog, schema and name, so nothing matches.
     */
    private static boolean anyEmpty(String... names) {
        for (String name : names) {
            if (name != null && name.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** A character string literal. */
    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /**
     * The condition (followed by AND) on a column for a name that's compared
     * exactly, or for a LIKE pattern (where "\" escapes "%" and "_"), or ""
     * for null, which matches anything.
     */
    private static String predicate(String column, String name, boolean pattern) {
        if (name == null) {
            return "";
        }
        return column + (pattern ? " LIKE " + literal(name) + " ESCAPE '\\' AND " : " = " + literal(name) + " AND ");
    }

    /**
     * The java.sql.Types code for the DATA_TYPE (an SQL/CLI code) and
     * TYPE_NAME of a column. A type that doesn't have an SQL/CLI code has a
     * null DATA_TYPE.
     */
    private static long jdbcType(Object dataType, Object typeName) {
        if (dataType != null) {
            return new Column("", (short) number(dataType), 0, (short) 0, true).jdbcType();
        }
        return switch (String.valueOf(typeName)) {
            case "BINARY" -> Types.BINARY;
            case "BINARY VARYING", "VARBINARY" -> Types.VARBINARY;
            case "DECFLOAT" -> Types.DECIMAL;
            default -> Types.OTHER;
        };
    }

    private static Column text(String name) {
        return new Column(name, Native.SQL_VARCHAR, 512, (short) 0, true);
    }

    private static Column smallint(String name) {
        return new Column(name, Native.SQL_SMALLINT, 0, (short) 0, true);
    }

    private static Column integer(String name) {
        return new Column(name, Native.SQL_INTEGER, 0, (short) 0, true);
    }

    private static Column bigint(String name) {
        return new Column(name, Native.SQL_BIGINT, 0, (short) 0, true);
    }

    private static Column booleanColumn(String name) {
        return new Column(name, Native.SQL_BOOLEAN, 0, (short) 0, true);
    }

    private static final Column[] TABLES = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("TABLE_TYPE"), text("REMARKS"),
        text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("SELF_REFERENCING_COL_NAME"),
        text("REF_GENERATION"),
    };

    /**
     * The tables, from SQLTables. A base table is a "TABLE", and a view of
     * the INFORMATION_SCHEMA is a "SYSTEM TABLE". The catalog is the name of
     * the database (such as ":memory:"), and isn't a pattern.
     */
    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        if (anyEmpty(catalog, schemaPattern, tableNamePattern) || (types != null && types.length == 0)) {
            return empty(TABLES);
        }

        // A table pattern of "%" (rather than null) is the same, but SQLTables
        // returns the catalogs, schemas or table types when there are no other
        // names.
        String table = tableNamePattern == null ? "%" : tableNamePattern;
        String typeList = types == null ? null : String.join(",", types);
        Rows r = rows(stmt -> Native.tables(stmt, catalog, schemaPattern, table, typeList));
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : r.rows()) {
            rows.add(new Object[] {
                r.get(row, "TABLE_CAT"), r.get(row, "TABLE_SCHEM"), r.get(row, "TABLE_NAME"),
                r.get(row, "TABLE_TYPE"), r.get(row, "REMARKS"), r.get(row, "UDT_CAT"), r.get(row, "UDT_SCHEM"),
                r.get(row, "UDT_NAME"), r.get(row, "SELF_REF_COLUMN"), r.get(row, "REF_GENERATION"),
            });
        }
        return new VsqlResultSet(TABLES, rows);
    }

    private static final Column[] CATALOGS = {text("TABLE_CAT")};

    /** The catalogs (databases) of the connection, from SQLTables. */
    @Override
    public ResultSet getCatalogs() throws SQLException {
        Rows r = rows(stmt -> Native.tables(stmt, Native.SQL_ALL_CATALOGS, null, null, null));
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : r.rows()) {
            rows.add(new Object[] {r.get(row, "TABLE_CAT")});
        }
        return new VsqlResultSet(CATALOGS, rows);
    }

    private static final Column[] SCHEMAS = {text("TABLE_SCHEM"), text("TABLE_CATALOG")};

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    /**
     * The schemas, from the INFORMATION_SCHEMA.SCHEMATA view, which (unlike
     * SQLTables) includes the schemas that don't have any tables. They include
     * the INFORMATION_SCHEMA and VSQL schemas.
     */
    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        if (anyEmpty(catalog, schemaPattern)) {
            return empty(SCHEMAS);
        }

        String sql = "SELECT SCHEMA_NAME, CATALOG_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE "
                + predicate("CATALOG_NAME", catalog, false) + predicate("SCHEMA_NAME", schemaPattern, true)
                + "1 = 1 ORDER BY CATALOG_NAME, SCHEMA_NAME";
        return new VsqlResultSet(SCHEMAS, rows(stmt -> Native.execDirect(stmt, sql)).rows());
    }

    private static final Column[] TABLE_TYPES = {text("TABLE_TYPE")};

    /**
     * The types of the tables, from SQLTables. "TABLE" is always one of them,
     * even when there aren't any tables yet.
     */
    @Override
    public ResultSet getTableTypes() throws SQLException {
        Rows r = rows(stmt -> Native.tables(stmt, null, null, null, Native.SQL_ALL_TABLE_TYPES));
        Set<String> types = new TreeSet<>();
        types.add("TABLE");
        for (Object[] row : r.rows()) {
            types.add((String) r.get(row, "TABLE_TYPE"));
        }
        List<Object[]> rows = new ArrayList<>();
        for (String type : types) {
            rows.add(new Object[] {type});
        }
        return new VsqlResultSet(TABLE_TYPES, rows);
    }

    private static final Column[] COLUMNS = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), integer("DATA_TYPE"),
        text("TYPE_NAME"), integer("COLUMN_SIZE"), integer("BUFFER_LENGTH"), integer("DECIMAL_DIGITS"),
        integer("NUM_PREC_RADIX"), integer("NULLABLE"), text("REMARKS"), text("COLUMN_DEF"),
        integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"), integer("CHAR_OCTET_LENGTH"),
        integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SCOPE_CATALOG"), text("SCOPE_SCHEMA"),
        text("SCOPE_TABLE"), smallint("SOURCE_DATA_TYPE"), text("IS_AUTOINCREMENT"), text("IS_GENERATEDCOLUMN"),
    };

    /**
     * The columns of the tables, from SQLColumns. DATA_TYPE is the
     * java.sql.Types code (see ResultSetMetaData.getColumnType), and TYPE_NAME
     * is the name of the type without its length or precision, such as
     * "CHARACTER VARYING". An identity column is IS_AUTOINCREMENT, and a
     * generated column (GENERATED ALWAYS AS) is IS_GENERATEDCOLUMN.
     */
    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        if (anyEmpty(catalog, schemaPattern, tableNamePattern, columnNamePattern)) {
            return empty(COLUMNS);
        }

        Rows r = rows(stmt -> Native.columns(stmt, catalog, schemaPattern, tableNamePattern, columnNamePattern));

        // SQLColumns doesn't have whether a column is an identity or generated
        // column.
        String sql = "SELECT TABLE_CATALOG, TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, IS_IDENTITY, IS_GENERATED "
                + "FROM INFORMATION_SCHEMA.COLUMNS WHERE " + predicate("TABLE_CATALOG", catalog, false)
                + predicate("TABLE_SCHEMA", schemaPattern, true) + predicate("TABLE_NAME", tableNamePattern, true)
                + predicate("COLUMN_NAME", columnNamePattern, true) + "1 = 1";
        Map<List<Object>, Object[]> generated = new HashMap<>();
        for (Object[] row : rows(stmt -> Native.execDirect(stmt, sql)).rows()) {
            generated.put(List.of(row[0], row[1], row[2], row[3]), row);
        }

        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : r.rows()) {
            Object[] g = generated.get(List.of(r.get(row, "TABLE_CAT"), r.get(row, "TABLE_SCHEM"),
                    r.get(row, "TABLE_NAME"), r.get(row, "COLUMN_NAME")));
            rows.add(new Object[] {
                r.get(row, "TABLE_CAT"), r.get(row, "TABLE_SCHEM"), r.get(row, "TABLE_NAME"),
                r.get(row, "COLUMN_NAME"), jdbcType(r.get(row, "DATA_TYPE"), r.get(row, "TYPE_NAME")),
                r.get(row, "TYPE_NAME"), r.get(row, "COLUMN_SIZE"), r.get(row, "BUFFER_LENGTH"),
                r.get(row, "DECIMAL_DIGITS"), r.get(row, "NUM_PREC_RADIX"), r.get(row, "NULLABLE"),
                r.get(row, "REMARKS"), r.get(row, "COLUMN_DEF"), r.get(row, "SQL_DATA_TYPE"),
                r.get(row, "SQL_DATETIME_SUB"), r.get(row, "CHAR_OCTET_LENGTH"), r.get(row, "ORDINAL_POSITION"),
                r.get(row, "IS_NULLABLE"), r.get(row, "SCOPE_CAT"), r.get(row, "SCOPE_SCHEM"),
                r.get(row, "SCOPE_NAME"), null,
                g == null ? "" : "YES".equals(g[4]) ? "YES" : "NO",
                g == null ? "" : "ALWAYS".equals(g[5]) ? "YES" : "NO",
            });
        }
        return new VsqlResultSet(COLUMNS, rows);
    }

    private static final Column[] PRIMARY_KEYS = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), smallint("KEY_SEQ"),
        text("PK_NAME"),
    };

    /**
     * The columns of the PRIMARY KEY of a table, from SQLPrimaryKeys. The
     * names aren't patterns, and the table can't be null.
     */
    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        if (anyEmpty(catalog, schema, table)) {
            return empty(PRIMARY_KEYS);
        }

        Rows r = rows(stmt -> Native.primaryKeys(stmt, catalog, schema, table));
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : r.rows()) {
            rows.add(new Object[] {
                r.get(row, "TABLE_CAT"), r.get(row, "TABLE_SCHEM"), r.get(row, "TABLE_NAME"),
                r.get(row, "COLUMN_NAME"), r.get(row, "ORDINAL_POSITION"), r.get(row, "PK_NAME"),
            });
        }
        rows.sort(Comparator.comparing(row -> (String) row[3]));
        return new VsqlResultSet(PRIMARY_KEYS, rows);
    }

    private static final Column[] FOREIGN_KEYS = {
        text("PKTABLE_CAT"), text("PKTABLE_SCHEM"), text("PKTABLE_NAME"), text("PKCOLUMN_NAME"),
        text("FKTABLE_CAT"), text("FKTABLE_SCHEM"), text("FKTABLE_NAME"), text("FKCOLUMN_NAME"),
        smallint("KEY_SEQ"), smallint("UPDATE_RULE"), smallint("DELETE_RULE"), text("FK_NAME"), text("PK_NAME"),
        smallint("DEFERRABILITY"),
    };

    /**
     * The foreign keys from SQLForeignKeys, which has the same codes for the
     * rules and deferrability as JDBC (such as importedKeyCascade). The rows
     * of each foreign key are together, ordered by their KEY_SEQ.
     */
    private List<Object[]> foreignKeys(String pkCatalog, String pkSchema, String pkTable, String fkCatalog,
            String fkSchema, String fkTable) throws SQLException {
        Rows r = rows(stmt -> Native.foreignKeys(stmt, pkCatalog, pkSchema, pkTable, fkCatalog, fkSchema,
                fkTable));
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : r.rows()) {
            rows.add(new Object[] {
                r.get(row, "UK_TABLE_CAT"), r.get(row, "UK_TABLE_SCHEM"), r.get(row, "UK_TABLE_NAME"),
                r.get(row, "UK_COLUMN_NAME"), r.get(row, "FK_TABLE_CAT"), r.get(row, "FK_TABLE_SCHEM"),
                r.get(row, "FK_TABLE_NAME"), r.get(row, "FK_COLUMN_NAME"), r.get(row, "ORDINAL_POSITION"),
                r.get(row, "UPDATE_RULE"), r.get(row, "DELETE_RULE"), r.get(row, "FK_NAME"), r.get(row, "UK_NAME"),
                r.get(row, "DEFERABILITY"),
            });
        }
        return rows;
    }

    private static void checkTable(String table) throws SQLException {
        if (table == null) {
            throw Errors.sql("HY009", "the table can't be null");
        }
    }

    /**
     * The columns of the foreign keys of a table (which can't be null), and
     * the PRIMARY KEY or UNIQUE columns that they reference, from
     * SQLForeignKeys.
     */
    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        checkTable(table);
        if (anyEmpty(catalog, schema, table)) {
            return empty(FOREIGN_KEYS);
        }

        List<Object[]> rows = foreignKeys(null, null, null, catalog, schema, table);
        rows.sort(Comparator.comparing((Object[] row) -> (String) row[0]).thenComparing(row -> (String) row[1])
                .thenComparing(row -> (String) row[2]));
        return new VsqlResultSet(FOREIGN_KEYS, rows);
    }

    /**
     * The columns of the foreign keys that reference the PRIMARY KEY of a
     * table (which can't be null), from SQLForeignKeys.
     */
    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        checkTable(table);
        if (anyEmpty(catalog, schema, table)) {
            return empty(FOREIGN_KEYS);
        }

        return new VsqlResultSet(FOREIGN_KEYS, foreignKeys(catalog, schema, table, null, null, null));
    }

    /**
     * The columns of the foreign keys of a table that reference the PRIMARY
     * KEY or a UNIQUE constraint of another table, from SQLForeignKeys.
     * Neither table can be null.
     */
    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
            String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        checkTable(parentTable);
        checkTable(foreignTable);
        if (anyEmpty(parentCatalog, parentSchema, parentTable, foreignCatalog, foreignSchema, foreignTable)) {
            return empty(FOREIGN_KEYS);
        }

        return new VsqlResultSet(FOREIGN_KEYS, foreignKeys(parentCatalog, parentSchema, parentTable,
                foreignCatalog, foreignSchema, foreignTable));
    }

    private static final Column[] BEST_ROW_IDENTIFIER = {
        smallint("SCOPE"), text("COLUMN_NAME"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("COLUMN_SIZE"),
        integer("BUFFER_LENGTH"), smallint("DECIMAL_DIGITS"), smallint("PSEUDO_COLUMN"),
    };

    /**
     * The columns that identify a row of a table, from SQLSpecialColumns:
     * the PRIMARY KEY or, without one, the UNIQUE constraint whose columns
     * are all NOT NULL with the fewest columns. They're valid for the session
     * (bestRowSession), and never NULL. The table can't be null.
     */
    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope,
            boolean nullable) throws SQLException {
        if (scope < bestRowTemporary || scope > bestRowSession) {
            throw Errors.sql("HY098", "scope out of range");
        }
        if (anyEmpty(catalog, schema, table)) {
            return empty(BEST_ROW_IDENTIFIER);
        }

        short n = nullable ? Native.SQL_NULLABLE : Native.SQL_NO_NULLS;
        Rows r = rows(stmt -> Native.specialColumns(stmt, catalog, schema, table, (short) scope, n));
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : r.rows()) {
            rows.add(new Object[] {
                r.get(row, "SCOPE"), r.get(row, "COLUMN_NAME"),
                jdbcType(r.get(row, "DATA_TYPE"), r.get(row, "TYPE_NAME")), r.get(row, "TYPE_NAME"),
                r.get(row, "COLUMN_SIZE"), r.get(row, "BUFFER_LENGTH"), r.get(row, "DECIMAL_DIGITS"),
                r.get(row, "PSEUDO_COLUMN"),
            });
        }
        return new VsqlResultSet(BEST_ROW_IDENTIFIER, rows);
    }

    private static final Column[] INDEX_INFO = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), booleanColumn("NON_UNIQUE"),
        text("INDEX_QUALIFIER"), text("INDEX_NAME"), smallint("TYPE"), smallint("ORDINAL_POSITION"), text("COLUMN_NAME"),
        text("ASC_OR_DESC"), bigint("CARDINALITY"), bigint("PAGES"), text("FILTER_CONDITION"),
    };

    /**
     * There's no SQL/CLI routine (or INFORMATION_SCHEMA view) for indexes, so
     * it's always empty.
     */
    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique,
            boolean approximate) throws SQLException {
        return empty(INDEX_INFO);
    }

    private static final Column[] VERSION_COLUMNS = {
        smallint("SCOPE"), text("COLUMN_NAME"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("COLUMN_SIZE"),
        integer("BUFFER_LENGTH"), smallint("DECIMAL_DIGITS"), smallint("PSEUDO_COLUMN"),
    };

    /** No column is updated automatically, so it's always empty. */
    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return empty(VERSION_COLUMNS);
    }

    private static final Column[] PSEUDO_COLUMNS = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), integer("DATA_TYPE"),
        integer("COLUMN_SIZE"), integer("DECIMAL_DIGITS"), integer("NUM_PREC_RADIX"), text("COLUMN_USAGE"),
        text("REMARKS"), integer("CHAR_OCTET_LENGTH"), text("IS_NULLABLE"),
    };

    /** There are no pseudo columns, so it's always empty. */
    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) throws SQLException {
        return empty(PSEUDO_COLUMNS);
    }

    private static final Column[] TABLE_PRIVILEGES = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("GRANTOR"), text("GRANTEE"),
        text("PRIVILEGE"), text("IS_GRANTABLE"),
    };

    /** vsql doesn't have privileges, so it's always empty. */
    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return empty(TABLE_PRIVILEGES);
    }

    private static final Column[] COLUMN_PRIVILEGES = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), text("GRANTOR"),
        text("GRANTEE"), text("PRIVILEGE"), text("IS_GRANTABLE"),
    };

    /** vsql doesn't have privileges, so it's always empty. */
    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        return empty(COLUMN_PRIVILEGES);
    }

    private static final Column[] PROCEDURES = {
        text("PROCEDURE_CAT"), text("PROCEDURE_SCHEM"), text("PROCEDURE_NAME"), text("RESERVED1"),
        text("RESERVED2"), text("RESERVED3"), text("REMARKS"), smallint("PROCEDURE_TYPE"), text("SPECIFIC_NAME"),
    };

    /** vsql doesn't have stored procedures, so it's always empty. */
    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        return empty(PROCEDURES);
    }

    private static final Column[] PROCEDURE_COLUMNS = {
        text("PROCEDURE_CAT"), text("PROCEDURE_SCHEM"), text("PROCEDURE_NAME"), text("COLUMN_NAME"),
        smallint("COLUMN_TYPE"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("PRECISION"), integer("LENGTH"),
        smallint("SCALE"), smallint("RADIX"), smallint("NULLABLE"), text("REMARKS"), text("COLUMN_DEF"),
        integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"), integer("CHAR_OCTET_LENGTH"),
        integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SPECIFIC_NAME"),
    };

    /** vsql doesn't have stored procedures, so it's always empty. */
    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
            String columnNamePattern) throws SQLException {
        return empty(PROCEDURE_COLUMNS);
    }

    private static final Column[] FUNCTIONS = {
        text("FUNCTION_CAT"), text("FUNCTION_SCHEM"), text("FUNCTION_NAME"), text("REMARKS"),
        smallint("FUNCTION_TYPE"), text("SPECIFIC_NAME"),
    };

    /**
     * vsql doesn't have user-defined functions (the built-in functions are in
     * getNumericFunctions and the others), so it's always empty.
     */
    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        return empty(FUNCTIONS);
    }

    private static final Column[] FUNCTION_COLUMNS = {
        text("FUNCTION_CAT"), text("FUNCTION_SCHEM"), text("FUNCTION_NAME"), text("COLUMN_NAME"),
        smallint("COLUMN_TYPE"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("PRECISION"), integer("LENGTH"),
        smallint("SCALE"), smallint("RADIX"), smallint("NULLABLE"), text("REMARKS"), integer("CHAR_OCTET_LENGTH"),
        integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SPECIFIC_NAME"),
    };

    /** See getFunctions, it's always empty. */
    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern,
            String columnNamePattern) throws SQLException {
        return empty(FUNCTION_COLUMNS);
    }

    private static final Column[] UDTS = {
        text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("CLASS_NAME"), integer("DATA_TYPE"),
        text("REMARKS"), smallint("BASE_TYPE"),
    };

    /** vsql doesn't have user-defined types, so it's always empty. */
    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        return empty(UDTS);
    }

    private static final Column[] SUPER_TYPES = {
        text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("SUPERTYPE_CAT"), text("SUPERTYPE_SCHEM"),
        text("SUPERTYPE_NAME"),
    };

    /** vsql doesn't have user-defined types, so it's always empty. */
    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
            throws SQLException {
        return empty(SUPER_TYPES);
    }

    private static final Column[] SUPER_TABLES = {
        text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("SUPERTABLE_NAME"),
    };

    /** vsql doesn't have typed tables, so it's always empty. */
    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return empty(SUPER_TABLES);
    }

    private static final Column[] ATTRIBUTES = {
        text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("ATTR_NAME"), integer("DATA_TYPE"),
        text("ATTR_TYPE_NAME"), integer("ATTR_SIZE"), integer("DECIMAL_DIGITS"), integer("NUM_PREC_RADIX"),
        integer("NULLABLE"), text("REMARKS"), text("ATTR_DEF"), integer("SQL_DATA_TYPE"),
        integer("SQL_DATETIME_SUB"), integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"),
        text("IS_NULLABLE"), text("SCOPE_CATALOG"), text("SCOPE_SCHEMA"), text("SCOPE_TABLE"),
        smallint("SOURCE_DATA_TYPE"),
    };

    /** vsql doesn't have user-defined types, so it's always empty. */
    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
            String attributeNamePattern) throws SQLException {
        return empty(ATTRIBUTES);
    }

    private static final Column[] CLIENT_INFO_PROPERTIES = {
        text("NAME"), integer("MAX_LEN"), text("DEFAULT_VALUE"), text("DESCRIPTION"),
    };

    /** There are no client info properties, so it's always empty. */
    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return empty(CLIENT_INFO_PROPERTIES);
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
        for (Object[] r : rows(Native::getTypeInfo).rows()) {
            rows.add(new Object[] {
                r[0], jdbcType(r[1], r[0]), r[2], r[3], r[4], r[5], r[6], bool(r[7]), r[8], bool(r[9]),
                bool(r[10]), bool(r[11]), r[12], r[13], r[14], r[15], r[16], r[17],
            });
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

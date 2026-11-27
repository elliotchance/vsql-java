package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The DatabaseMetaData methods that describe vsql and the driver. */
class DatabaseMetaDataValuesTest {
    @TempDir
    Path tmp;

    /** A connection to a new in-memory database. */
    Connection conn;

    DatabaseMetaData db;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
        db = conn.getMetaData();
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

    List<String> column(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    /**
     * Every method that returns a value (rather than a result set) returns
     * one, for each result set type when it takes one.
     */
    @Test
    void everyValueIsSupported() throws Exception {
        Set<Class<?>> values = Set.of(boolean.class, int.class, long.class, String.class, RowIdLifetime.class);
        int[] types = {ResultSet.TYPE_FORWARD_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
            ResultSet.TYPE_SCROLL_SENSITIVE};
        for (Method m : DatabaseMetaData.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || !values.contains(m.getReturnType())) {
                continue;
            }
            try {
                if (m.getParameterCount() == 0) {
                    m.invoke(db);
                } else if (Arrays.equals(m.getParameterTypes(), new Class<?>[] {int.class})) {
                    for (int type : types) {
                        m.invoke(db, type);
                    }
                }
            } catch (InvocationTargetException e) {
                throw new AssertionError(m.getName() + " threw " + e.getCause(), e.getCause());
            }
        }
    }

    @Test
    void terms() throws SQLException {
        assertEquals("catalog", db.getCatalogTerm());
        assertEquals("schema", db.getSchemaTerm());
        assertEquals("procedure", db.getProcedureTerm());
        assertEquals(".", db.getCatalogSeparator());
        assertTrue(db.isCatalogAtStart());
        assertEquals("\\", db.getSearchStringEscape());
        assertEquals("", db.getExtraNameCharacters());
        assertEquals(RowIdLifetime.ROWID_UNSUPPORTED, db.getRowIdLifetime());
    }

    @Test
    void catalogsAndSchemas() throws SQLException {
        assertTrue(db.supportsCatalogsInDataManipulation());
        assertTrue(db.supportsCatalogsInTableDefinitions());
        assertTrue(db.supportsSchemasInDataManipulation());
        assertTrue(db.supportsSchemasInTableDefinitions());
        assertFalse(db.supportsSchemasInPrivilegeDefinitions());
        assertFalse(db.supportsCatalogsInProcedureCalls());

        exec("CREATE SCHEMA s");
        exec("CREATE TABLE \":memory:\".s.t (x INT)");
        exec("INSERT INTO \":memory:\".s.t (x) VALUES (1)");
        assertEquals(List.of("1"), column("SELECT x FROM s.t"));
    }

    @Test
    void keywords() throws SQLException {
        List<String> keywords = Arrays.asList(db.getSQLKeywords().split(","));
        assertTrue(keywords.contains("TRUNCATE"));
        assertTrue(keywords.contains("JSON_TABLE"));
        // SQL:2003 reserved words aren't included.
        assertFalse(keywords.contains("SELECT"));
        assertFalse(keywords.contains("MERGE"));
        // Nor are the words that vsql doesn't reserve.
        assertFalse(keywords.contains("VACUUM"));

        // Each one is reserved (it can't be a name unless it's quoted).
        for (String keyword : List.of("TRUNCATE", "OFFSET")) {
            assertThrows(SQLException.class, () -> exec("CREATE TABLE " + keyword + " (x INT)"));
            exec("CREATE TABLE \"" + keyword + "\" (x INT)");
        }
    }

    @Test
    void functions() throws SQLException {
        for (String f : db.getNumericFunctions().split(",")) {
            String args = switch (f) {
                case "ATAN2", "POWER" -> "(1e0, 2e0)";
                case "MOD" -> "(5, 2)";
                default -> "(1e0)";
            };
            assertEquals(1, column("SELECT " + f + args).size(), f);
        }
        for (String f : db.getStringFunctions().split(",")) {
            String args = switch (f) {
                case "CONCAT" -> "('a', 'b')";
                case "POSITION" -> "('a' IN 'abc')";
                case "REPEAT" -> "('a', 2)";
                case "REPLACE" -> "('abc', 'b', 'x')";
                case "SUBSTRING" -> "('abc' FROM 2)";
                default -> "('abc')";
            };
            assertEquals(1, column("SELECT " + f + args).size(), f);
        }
        assertEquals("", db.getSystemFunctions());
        assertEquals(1, column("SELECT EXTRACT(YEAR FROM CURRENT_DATE)").size());
        assertEquals(1, column("SELECT NOW()").size());
        assertFalse(db.supportsConvert());
        assertFalse(db.supportsConvert(java.sql.Types.INTEGER, java.sql.Types.VARCHAR));
    }

    @Test
    void limits() throws SQLException {
        assertEquals(128, db.getMaxTableNameLength());
        assertEquals(128, db.getMaxColumnNameLength());
        assertEquals(128, db.getMaxSchemaNameLength());
        assertEquals(128, db.getMaxCatalogNameLength());
        assertEquals(128, db.getMaxCursorNameLength());
        assertEquals(128, db.getMaxProcedureNameLength());
        assertEquals(1600, db.getMaxColumnsInTable());

        // 0 is no limit.
        assertEquals(0, db.getMaxConnections());
        assertEquals(0, db.getMaxStatements());
        assertEquals(0, db.getMaxStatementLength());
        assertEquals(0, db.getMaxColumnsInSelect());
        assertEquals(0, db.getMaxTablesInSelect());
        assertEquals(0, db.getMaxRowSize());
        assertFalse(db.doesMaxRowSizeIncludeBlobs());

        String name = "t".repeat(db.getMaxTableNameLength());
        exec("CREATE TABLE " + name + " (x INT)");
        assertThrows(SQLException.class, () -> exec("CREATE TABLE " + name + "x (x INT)"));
    }

    @Test
    void nulls() throws SQLException {
        assertTrue(db.nullsAreSortedLow());
        assertFalse(db.nullsAreSortedHigh());
        assertFalse(db.nullsAreSortedAtStart());
        assertFalse(db.nullsAreSortedAtEnd());
        assertTrue(db.nullPlusNonNullIsNull());

        exec("CREATE TABLE t (x INT)");
        exec("INSERT INTO t (x) VALUES (2), (NULL), (1)");
        assertEquals(Arrays.asList(null, "1", "2"), column("SELECT x FROM t ORDER BY x"));
        assertEquals(Arrays.asList("2", "1", null), column("SELECT x FROM t ORDER BY x DESC"));
    }

    @Test
    void identifiers() throws SQLException {
        assertFalse(db.storesLowerCaseQuotedIdentifiers());
        assertFalse(db.storesUpperCaseQuotedIdentifiers());
        assertTrue(db.storesMixedCaseQuotedIdentifiers());
        assertFalse(db.supportsDifferentTableCorrelationNames());
        assertTrue(db.supportsTableCorrelationNames());
        assertTrue(db.supportsColumnAliasing());

        exec("CREATE TABLE t (x INT)");
        exec("INSERT INTO t (x) VALUES (1)");
        assertEquals(List.of("1"), column("SELECT t.x AS y FROM t AS t"));
    }

    @Test
    void transactions() throws SQLException {
        assertTrue(db.supportsDataDefinitionAndDataManipulationTransactions());
        assertFalse(db.supportsDataManipulationTransactionsOnly());
        assertFalse(db.dataDefinitionCausesTransactionCommit());
        assertFalse(db.dataDefinitionIgnoredInTransactions());
        assertTrue(db.supportsMultipleTransactions());
        assertFalse(db.supportsOpenCursorsAcrossCommit());
        assertFalse(db.supportsOpenCursorsAcrossRollback());
        assertTrue(db.supportsOpenStatementsAcrossCommit());
        assertTrue(db.supportsOpenStatementsAcrossRollback());

        // A CREATE TABLE is rolled back.
        conn.setAutoCommit(false);
        exec("CREATE TABLE t (x INT)");
        conn.rollback();
        assertThrows(SQLException.class, () -> exec("SELECT * FROM t"));
        conn.rollback();
    }

    @Test
    void joins() throws SQLException {
        assertTrue(db.supportsOuterJoins());
        assertTrue(db.supportsLimitedOuterJoins());
        assertTrue(db.supportsFullOuterJoins());

        exec("CREATE TABLE a (x INT)");
        exec("CREATE TABLE b (x INT)");
        exec("INSERT INTO a (x) VALUES (1), (2)");
        exec("INSERT INTO b (x) VALUES (2), (3)");
        assertEquals(Arrays.asList("1", "2", null),
                column("SELECT a.x FROM a FULL OUTER JOIN b ON a.x = b.x ORDER BY a.x NULLS LAST"));
        assertEquals(Arrays.asList("1", "2"), column("SELECT a.x FROM a LEFT JOIN b ON a.x = b.x ORDER BY a.x"));
    }

    @Test
    void queries() throws SQLException {
        assertTrue(db.supportsLikeEscapeClause());
        assertTrue(db.supportsUnion());
        assertTrue(db.supportsUnionAll());
        assertTrue(db.supportsGroupBy());
        assertTrue(db.supportsGroupByUnrelated());
        assertTrue(db.supportsGroupByBeyondSelect());
        assertTrue(db.supportsOrderByUnrelated());
        assertTrue(db.supportsExpressionsInOrderBy());
        assertTrue(db.supportsSubqueriesInComparisons());
        assertTrue(db.supportsSubqueriesInExists());
        assertTrue(db.supportsSubqueriesInIns());
        assertTrue(db.supportsSubqueriesInQuantifieds());
        assertTrue(db.supportsCorrelatedSubqueries());
        assertTrue(db.supportsSelectForUpdate());
        assertTrue(db.supportsNonNullableColumns());
        assertTrue(db.supportsIntegrityEnhancementFacility());

        exec("CREATE TABLE t (x INT NOT NULL, y VARCHAR(10))");
        exec("INSERT INTO t (x, y) VALUES (1, 'a%'), (2, 'ab'), (3, 'ab')");
        assertEquals(List.of("1"), column("SELECT x FROM t WHERE y LIKE 'a!%' ESCAPE '!'"));
        assertEquals(List.of("1", "2", "3"), column("SELECT x FROM t UNION SELECT x FROM t ORDER BY x"));
        assertEquals(List.of("2", "1"), column("SELECT COUNT(*) FROM t GROUP BY y ORDER BY COUNT(*) DESC"));
        assertEquals(List.of("3", "2", "1"), column("SELECT x FROM t ORDER BY -x"));
        assertEquals(List.of("a%", "ab", "ab"), column("SELECT y FROM t ORDER BY x"));
        assertEquals(List.of("2", "3"),
                column("SELECT x FROM t AS o WHERE EXISTS (SELECT 1 FROM t WHERE y = o.y AND x <> o.x) ORDER BY x"));
        assertEquals(List.of("3"), column("SELECT x FROM t WHERE x > ALL (SELECT x FROM t WHERE x < 3)"));
        assertEquals(List.of("1", "2", "3"), column("SELECT x FROM t ORDER BY x FOR UPDATE"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO t (x) VALUES (NULL)"));
    }

    @Test
    void alterTable() throws SQLException {
        assertTrue(db.supportsAlterTableWithAddColumn());
        assertTrue(db.supportsAlterTableWithDropColumn());

        exec("CREATE TABLE t (x INT)");
        exec("ALTER TABLE t ADD COLUMN y INT");
        exec("ALTER TABLE t DROP COLUMN x");
        exec("INSERT INTO t (y) VALUES (1)");
        assertEquals(List.of("1"), column("SELECT * FROM t"));
    }

    @Test
    void notSupported() throws SQLException {
        assertTrue(db.supportsPositionedDelete());
        assertTrue(db.supportsPositionedUpdate());
        assertFalse(db.supportsStatementPooling());
        assertFalse(db.supportsStoredFunctionsUsingCallSyntax());
        assertFalse(db.supportsMultipleOpenResults());
        assertFalse(db.usesLocalFilePerTable());
        assertFalse(db.supportsExtendedSQLGrammar());
        assertTrue(db.supportsMinimumSQLGrammar());
        assertTrue(db.supportsCoreSQLGrammar());
        assertTrue(db.supportsANSI92EntryLevelSQL());
        assertFalse(db.supportsANSI92FullSQL());
        for (int type : new int[] {ResultSet.TYPE_FORWARD_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE}) {
            assertFalse(db.ownUpdatesAreVisible(type));
            assertFalse(db.othersInsertsAreVisible(type));
            assertFalse(db.deletesAreDetected(type));
        }
    }

    /** The features are read in a transaction, and from a file database. */
    @Test
    void featuresInATransaction() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:vsql:" + tmp.resolve("test.vsql"))) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE t (x INT)");
            }
            DatabaseMetaData md = c.getMetaData();
            assertTrue(md.supportsFullOuterJoins());
            assertTrue(md.supportsUnion());
            c.commit();
        }
    }

    @Test
    void closedConnection() throws SQLException {
        conn.close();
        assertThrows(SQLException.class, () -> db.supportsFullOuterJoins());
        assertThrows(SQLException.class, () -> db.getMaxTableNameLength());
        assertThrows(SQLException.class, () -> db.nullsAreSortedLow());
    }
}

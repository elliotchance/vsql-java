package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The DatabaseMetaData methods that describe the database's objects. */
class DatabaseMetaDataObjectsTest {
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

    /** The values of a column of each row, which closes the result set. */
    static List<String> column(ResultSet rs, String name) throws SQLException {
        List<String> values = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                values.add(rs.getString(name));
            }
        }
        return values;
    }

    static List<String> columnNames(ResultSet rs) throws SQLException {
        try (rs) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                names.add(md.getColumnName(i));
            }
            return names;
        }
    }

    void createTables() throws SQLException {
        exec("CREATE SCHEMA s");
        exec("CREATE TABLE parent (id INT PRIMARY KEY, u INT NOT NULL UNIQUE)");
        exec("CREATE TABLE child (x INT REFERENCES parent ON DELETE CASCADE, y INT REFERENCES parent (u))");
        exec("CREATE TABLE s.pair (b INT NOT NULL, a INT NOT NULL, PRIMARY KEY (b, a))");
        exec("CREATE TABLE s.uniq (k INT, n INT NOT NULL, m INT NOT NULL, UNIQUE (n, m))");
    }

    @Test
    void tables() throws SQLException {
        createTables();
        assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS", "TYPE_CAT",
                "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"),
                columnNames(db.getTables(null, null, null, null)));

        // Ordered by TABLE_TYPE, TABLE_CAT, TABLE_SCHEM and TABLE_NAME.
        String[] tables = {"TABLE"};
        assertEquals(List.of("CHILD", "PARENT", "PAIR", "UNIQ"),
                column(db.getTables(null, null, null, tables), "TABLE_NAME"));
        assertEquals(List.of("PUBLIC", "PUBLIC", "S", "S"),
                column(db.getTables(null, null, "%", tables), "TABLE_SCHEM"));
        assertEquals(List.of(":memory:", ":memory:", ":memory:", ":memory:"),
                column(db.getTables(":memory:", null, null, tables), "TABLE_CAT"));
        try (ResultSet rs = db.getTables(null, "PUBLIC", "PARENT", null)) {
            assertTrue(rs.next());
            assertEquals("TABLE", rs.getString("TABLE_TYPE"));
            assertNull(rs.getString("REMARKS"));
            assertFalse(rs.next());
        }

        // The views of the INFORMATION_SCHEMA are system tables.
        assertEquals(List.of("SYSTEM TABLE"),
                column(db.getTables(null, "INFORMATION_SCHEMA", "TABLES", null), "TABLE_TYPE"));
        assertEquals(List.of(), column(db.getTables(null, "INFORMATION_SCHEMA", null, tables), "TABLE_NAME"));
        assertTrue(column(db.getTables(null, null, null, new String[] {"SYSTEM TABLE", "TABLE"}), "TABLE_NAME")
                .containsAll(List.of("TABLES", "COLUMNS", "PARENT")));
    }

    @Test
    void tablePatterns() throws SQLException {
        createTables();
        exec("CREATE TABLE a_b (x INT)");
        exec("CREATE TABLE axb (x INT)");
        assertEquals(List.of("PARENT", "PAIR"), column(db.getTables(null, null, "PA%", new String[] {"TABLE"}), "TABLE_NAME"));
        assertEquals(List.of("AXB", "A_B"), column(db.getTables(null, "PUBLIC", "A_B", null), "TABLE_NAME"));
        assertEquals(List.of("A_B"), column(db.getTables(null, "PUBLIC", "A\\_B", null), "TABLE_NAME"));
        assertEquals(List.of("PAIR", "UNIQ"), column(db.getTables(null, "_", null, null), "TABLE_NAME"));

        // The names are case sensitive (and stored in upper case).
        assertEquals(List.of(), column(db.getTables(null, null, "parent", null), "TABLE_NAME"));

        // The catalog isn't a pattern.
        assertEquals(List.of(), column(db.getTables(":mem%", null, null, null), "TABLE_NAME"));
        assertEquals(List.of(), column(db.getTables("nope", null, null, null), "TABLE_NAME"));

        // A zero-length string is an object without one, which there aren't.
        assertEquals(List.of(), column(db.getTables("", null, null, null), "TABLE_NAME"));
        assertEquals(List.of(), column(db.getTables(null, "", null, null), "TABLE_NAME"));
        assertEquals(List.of(), column(db.getTables(null, null, null, new String[0]), "TABLE_NAME"));
    }

    @Test
    void catalogsSchemasAndTableTypes() throws SQLException {
        createTables();
        exec("CREATE SCHEMA unused");
        assertEquals(List.of("TABLE_CAT"), columnNames(db.getCatalogs()));
        assertEquals(List.of(":memory:"), column(db.getCatalogs(), "TABLE_CAT"));

        assertEquals(List.of("TABLE_SCHEM", "TABLE_CATALOG"), columnNames(db.getSchemas()));
        List<String> schemas = column(db.getSchemas(), "TABLE_SCHEM");
        assertTrue(schemas.containsAll(List.of("INFORMATION_SCHEMA", "PUBLIC", "S", "UNUSED")), schemas.toString());
        assertEquals(schemas.stream().sorted().toList(), schemas);
        assertEquals(List.of(":memory:"), column(db.getSchemas(":memory:", "PUBLIC"), "TABLE_CATALOG"));
        assertEquals(List.of("UNUSED"), column(db.getSchemas(null, "U%"), "TABLE_SCHEM"));
        assertEquals(List.of(), column(db.getSchemas("nope", null), "TABLE_SCHEM"));
        assertEquals(List.of(), column(db.getSchemas(null, ""), "TABLE_SCHEM"));

        assertEquals(List.of("SYSTEM TABLE", "TABLE"), column(db.getTableTypes(), "TABLE_TYPE"));
    }

    /** "TABLE" is a table type before there are any tables. */
    @Test
    void tableTypesWithoutTables() throws SQLException {
        assertTrue(column(db.getTableTypes(), "TABLE_TYPE").contains("TABLE"));
    }

    @Test
    void columns() throws SQLException {
        exec("CREATE TABLE t (a INT NOT NULL, b VARCHAR(10) DEFAULT 'x', c NUMERIC(5, 2), d TIMESTAMP(3), "
                + "e VARBINARY(8), f BOOLEAN, g INT GENERATED ALWAYS AS IDENTITY, h INT GENERATED ALWAYS AS (a + 1))");
        assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS",
                "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
                "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
                "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"), columnNames(db.getColumns(null, null, "T", null)));

        int[] types = {Types.INTEGER, Types.VARCHAR, Types.NUMERIC, Types.TIMESTAMP, Types.VARBINARY,
            Types.BOOLEAN, Types.INTEGER, Types.INTEGER};
        try (ResultSet rs = db.getColumns(null, "PUBLIC", "T", null)) {
            for (int i = 0; i < types.length; i++) {
                assertTrue(rs.next());
                assertEquals(":memory:", rs.getString("TABLE_CAT"));
                assertEquals("PUBLIC", rs.getString("TABLE_SCHEM"));
                assertEquals("T", rs.getString("TABLE_NAME"));
                assertEquals(String.valueOf((char) ('A' + i)), rs.getString("COLUMN_NAME"));
                assertEquals(types[i], rs.getInt("DATA_TYPE"), rs.getString("COLUMN_NAME"));
                assertEquals(i + 1, rs.getInt("ORDINAL_POSITION"));
            }
            assertFalse(rs.next());
        }

        assertEquals(List.of("INTEGER", "CHARACTER VARYING", "NUMERIC", "TIMESTAMP"),
                column(db.getColumns(null, null, "T", "_"), "TYPE_NAME").subList(0, 4));
        assertEquals(List.of("10", "10", "5", "23"),
                column(db.getColumns(null, null, "T", null), "COLUMN_SIZE").subList(0, 4));
        assertEquals(Arrays.asList("0", null, "2", "3"),
                column(db.getColumns(null, null, "T", null), "DECIMAL_DIGITS").subList(0, 4));
        assertEquals(List.of("0", "1", "1", "1"),
                column(db.getColumns(null, null, "T", null), "NULLABLE").subList(0, 4));
        assertEquals(List.of("NO", "YES", "YES", "YES"),
                column(db.getColumns(null, null, "T", null), "IS_NULLABLE").subList(0, 4));
        assertEquals(List.of("'x'"), column(db.getColumns(null, null, "T", "B"), "COLUMN_DEF"));
        assertEquals(List.of("NO", "NO", "NO", "NO", "NO", "NO", "YES", "NO"),
                column(db.getColumns(null, null, "T", null), "IS_AUTOINCREMENT"));
        assertEquals(List.of("NO", "NO", "NO", "NO", "NO", "NO", "NO", "YES"),
                column(db.getColumns(null, null, "T", null), "IS_GENERATEDCOLUMN"));

        // The columns are patterns.
        assertEquals(List.of("A", "B", "C", "D", "E", "F", "G", "H"),
                column(db.getColumns(null, null, "T", "_"), "COLUMN_NAME"));
        assertEquals(List.of("B"), column(db.getColumns(null, "P%", "_", "B"), "COLUMN_NAME"));
        assertEquals(List.of(), column(db.getColumns(null, null, "T", "Z%"), "COLUMN_NAME"));
        assertEquals(List.of(), column(db.getColumns(null, null, "T", ""), "COLUMN_NAME"));
    }

    @Test
    void primaryKeys() throws SQLException {
        createTables();
        assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"),
                columnNames(db.getPrimaryKeys(null, null, "PARENT")));
        assertEquals(List.of("ID"), column(db.getPrimaryKeys(null, null, "PARENT"), "COLUMN_NAME"));
        assertEquals(List.of("PARENT_PRIMARY_KEY"), column(db.getPrimaryKeys(null, "PUBLIC", "PARENT"), "PK_NAME"));

        // Ordered by COLUMN_NAME, with the position in the key.
        assertEquals(List.of("A", "B"), column(db.getPrimaryKeys(":memory:", "S", "PAIR"), "COLUMN_NAME"));
        assertEquals(List.of("2", "1"), column(db.getPrimaryKeys(":memory:", "S", "PAIR"), "KEY_SEQ"));

        // A table without a PRIMARY KEY, or that doesn't exist. The table
        // isn't a pattern.
        assertEquals(List.of(), column(db.getPrimaryKeys(null, null, "CHILD"), "COLUMN_NAME"));
        assertEquals(List.of(), column(db.getPrimaryKeys(null, null, "PAR%"), "COLUMN_NAME"));
        assertEquals(List.of(), column(db.getPrimaryKeys(null, "PUBLIC", "PAIR"), "COLUMN_NAME"));
        assertThrows(SQLException.class, () -> db.getPrimaryKeys(null, null, null));
    }

    @Test
    void foreignKeys() throws SQLException {
        createTables();
        List<String> columns = List.of("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME",
                "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "UPDATE_RULE",
                "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY");
        assertEquals(columns, columnNames(db.getImportedKeys(null, null, "CHILD")));
        assertEquals(columns, columnNames(db.getExportedKeys(null, null, "PARENT")));
        assertEquals(columns, columnNames(db.getCrossReference(null, null, "PARENT", null, null, "CHILD")));

        // The foreign keys of CHILD, which reference the PRIMARY KEY and the
        // UNIQUE of PARENT.
        try (ResultSet rs = db.getImportedKeys(null, "PUBLIC", "CHILD")) {
            assertTrue(rs.next());
            assertEquals(":memory:", rs.getString("PKTABLE_CAT"));
            assertEquals("PUBLIC", rs.getString("PKTABLE_SCHEM"));
            assertEquals("PARENT", rs.getString("PKTABLE_NAME"));
            assertEquals("ID", rs.getString("PKCOLUMN_NAME"));
            assertEquals("CHILD", rs.getString("FKTABLE_NAME"));
            assertEquals("X", rs.getString("FKCOLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"));
            assertEquals(DatabaseMetaData.importedKeyNoAction, rs.getShort("UPDATE_RULE"));
            assertEquals(DatabaseMetaData.importedKeyCascade, rs.getShort("DELETE_RULE"));
            assertEquals("CHILD_FOREIGN_KEY_1", rs.getString("FK_NAME"));
            assertEquals("PARENT_PRIMARY_KEY", rs.getString("PK_NAME"));
            assertEquals(DatabaseMetaData.importedKeyNotDeferrable, rs.getShort("DEFERRABILITY"));

            assertTrue(rs.next());
            assertEquals("U", rs.getString("PKCOLUMN_NAME"));
            assertEquals("Y", rs.getString("FKCOLUMN_NAME"));
            assertEquals(DatabaseMetaData.importedKeyNoAction, rs.getShort("DELETE_RULE"));
            assertEquals("CHILD_FOREIGN_KEY_2", rs.getString("FK_NAME"));
            assertEquals("PARENT_UNIQUE_1", rs.getString("PK_NAME"));
            assertFalse(rs.next());
        }

        // Only the foreign keys that reference the PRIMARY KEY are exported.
        assertEquals(List.of("X"), column(db.getExportedKeys(null, null, "PARENT"), "FKCOLUMN_NAME"));
        assertEquals(List.of("X", "Y"),
                column(db.getCrossReference(null, null, "PARENT", null, "PUBLIC", "CHILD"), "FKCOLUMN_NAME"));
        assertEquals(List.of(), column(db.getCrossReference(null, null, "CHILD", null, null, "PARENT"),
                "FKCOLUMN_NAME"));
        assertEquals(List.of(), column(db.getImportedKeys(null, null, "PARENT"), "FKCOLUMN_NAME"));
        assertEquals(List.of(), column(db.getExportedKeys(null, "S", "PARENT"), "FKCOLUMN_NAME"));
        assertEquals(List.of(), column(db.getImportedKeys("", null, "CHILD"), "FKCOLUMN_NAME"));

        assertThrows(SQLException.class, () -> db.getImportedKeys(null, null, null));
        assertThrows(SQLException.class, () -> db.getExportedKeys(null, null, null));
        assertThrows(SQLException.class, () -> db.getCrossReference(null, null, "PARENT", null, null, null));
    }

    /** A multi-column foreign key, across schemas. */
    @Test
    void multiColumnForeignKey() throws SQLException {
        createTables();
        exec("CREATE TABLE c (q INT, p INT, FOREIGN KEY (p, q) REFERENCES s.pair (b, a))");
        try (ResultSet rs = db.getImportedKeys(null, null, "C")) {
            assertTrue(rs.next());
            assertEquals("S", rs.getString("PKTABLE_SCHEM"));
            assertEquals("B", rs.getString("PKCOLUMN_NAME"));
            assertEquals("P", rs.getString("FKCOLUMN_NAME"));
            assertEquals(1, rs.getShort("KEY_SEQ"));
            assertTrue(rs.next());
            assertEquals("A", rs.getString("PKCOLUMN_NAME"));
            assertEquals("Q", rs.getString("FKCOLUMN_NAME"));
            assertEquals(2, rs.getShort("KEY_SEQ"));
            assertFalse(rs.next());
        }
        assertEquals(List.of("C", "C"), column(db.getExportedKeys(null, "S", "PAIR"), "FKTABLE_NAME"));
    }

    @Test
    void bestRowIdentifier() throws SQLException {
        createTables();
        assertEquals(List.of("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                "DECIMAL_DIGITS", "PSEUDO_COLUMN"),
                columnNames(db.getBestRowIdentifier(null, null, "PARENT", DatabaseMetaData.bestRowSession, false)));

        // The PRIMARY KEY.
        try (ResultSet rs = db.getBestRowIdentifier(null, null, "PARENT", DatabaseMetaData.bestRowTemporary,
                true)) {
            assertTrue(rs.next());
            assertEquals(DatabaseMetaData.bestRowSession, rs.getShort("SCOPE"));
            assertEquals("ID", rs.getString("COLUMN_NAME"));
            assertEquals(Types.INTEGER, rs.getInt("DATA_TYPE"));
            assertEquals("INTEGER", rs.getString("TYPE_NAME"));
            assertEquals(DatabaseMetaData.bestRowNotPseudo, rs.getShort("PSEUDO_COLUMN"));
            assertFalse(rs.next());
        }
        assertEquals(List.of("B", "A"), column(db.getBestRowIdentifier(null, "S", "PAIR",
                DatabaseMetaData.bestRowSession, false), "COLUMN_NAME"));

        // A UNIQUE whose columns are NOT NULL, without a PRIMARY KEY.
        assertEquals(List.of("N", "M"), column(db.getBestRowIdentifier(null, "S", "UNIQ",
                DatabaseMetaData.bestRowTransaction, false), "COLUMN_NAME"));

        // Neither.
        assertEquals(List.of(), column(db.getBestRowIdentifier(null, null, "CHILD",
                DatabaseMetaData.bestRowSession, false), "COLUMN_NAME"));

        assertThrows(SQLException.class, () -> db.getBestRowIdentifier(null, null, "PARENT", 3, false));
        assertThrows(SQLException.class,
                () -> db.getBestRowIdentifier(null, null, null, DatabaseMetaData.bestRowSession, false));
    }

    /** The objects that vsql doesn't have are result sets without rows. */
    @Test
    void empty() throws SQLException {
        createTables();
        assertEmpty(db.getProcedures(null, null, null), "PROCEDURE_CAT", 9);
        assertEmpty(db.getProcedureColumns(null, null, null, null), "PROCEDURE_CAT", 20);
        assertEmpty(db.getFunctions(null, null, null), "FUNCTION_CAT", 6);
        assertEmpty(db.getFunctionColumns(null, null, null, null), "FUNCTION_CAT", 17);
        assertEmpty(db.getUDTs(null, null, null, null), "TYPE_CAT", 7);
        assertEmpty(db.getSuperTypes(null, null, null), "TYPE_CAT", 6);
        assertEmpty(db.getSuperTables(null, null, null), "TABLE_CAT", 4);
        assertEmpty(db.getAttributes(null, null, null, null), "TYPE_CAT", 21);
        assertEmpty(db.getPseudoColumns(null, null, null, null), "TABLE_CAT", 12);
        assertEmpty(db.getVersionColumns(null, null, "PARENT"), "SCOPE", 8);
        assertEmpty(db.getClientInfoProperties(), "NAME", 4);
        assertEmpty(db.getTablePrivileges(null, null, null), "TABLE_CAT", 7);
        assertEmpty(db.getColumnPrivileges(null, null, "PARENT", null), "TABLE_CAT", 8);
        assertEmpty(db.getIndexInfo(null, null, "PARENT", false, true), "TABLE_CAT", 13);
    }

    static void assertEmpty(ResultSet rs, String first, int columns) throws SQLException {
        try (rs) {
            assertEquals(first, rs.getMetaData().getColumnName(1));
            assertEquals(columns, rs.getMetaData().getColumnCount());
            assertFalse(rs.next());
        }
    }

    /** The result sets are in memory, so they can be read in any order. */
    @Test
    void openAtTheSameTime() throws SQLException {
        createTables();
        try (ResultSet tables = db.getTables(null, null, null, new String[] {"TABLE"})) {
            int n = 0;
            while (tables.next()) {
                try (ResultSet columns = db.getColumns(tables.getString("TABLE_CAT"),
                        tables.getString("TABLE_SCHEM"), tables.getString("TABLE_NAME"), null)) {
                    assertTrue(columns.next());
                }
                n++;
            }
            assertEquals(4, n);
        }
    }

    /** The objects of a file database, in a transaction. */
    @Test
    void fileDatabase() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:vsql:" + tmp.resolve("test.vsql"))) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE foo (id INT PRIMARY KEY)");
            }
            DatabaseMetaData md = c.getMetaData();
            // The catalog is the name of the file.
            assertEquals(List.of("test"), column(md.getCatalogs(), "TABLE_CAT"));
            assertEquals(List.of("FOO"), column(md.getTables("test", "PUBLIC", null, new String[] {"TABLE"}),
                    "TABLE_NAME"));
            assertEquals(List.of("ID"), column(md.getPrimaryKeys("test", "PUBLIC", "FOO"), "COLUMN_NAME"));
            c.rollback();
            assertEquals(List.of(), column(md.getTables(null, null, "FOO", null), "TABLE_NAME"));
        }
    }

    @Test
    void closedConnection() throws SQLException {
        conn.close();
        assertThrows(SQLException.class, () -> db.getTables(null, null, null, null));
        assertThrows(SQLException.class, () -> db.getColumns(null, null, null, null));
        assertThrows(SQLException.class, () -> db.getProcedures(null, null, null));
    }
}

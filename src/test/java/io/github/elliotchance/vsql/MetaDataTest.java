package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DatabaseMetaData, ResultSetMetaData and ParameterMetaData. */
class MetaDataTest {
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    @Test
    void database() throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        assertSame(conn, md.getConnection());
        assertNull(md.getUserName());
        assertEquals("vsql", md.getDriverName());
        assertEquals(Version.DRIVER, md.getDriverVersion());
        assertEquals(Version.part(Version.DRIVER, 0), md.getDriverMajorVersion());
        assertEquals(Version.part(Version.DRIVER, 1), md.getDriverMinorVersion());
        assertEquals(4, md.getJDBCMajorVersion());
        assertEquals(3, md.getJDBCMinorVersion());
        assertEquals(DatabaseMetaData.sqlStateSQL, md.getSQLStateType());
        assertFalse(md.isReadOnly());
        assertTrue(md.supportsTransactions());
        assertTrue(md.supportsBatchUpdates());
        assertEquals("\"", md.getIdentifierQuoteString());
        assertFalse(md.supportsMixedCaseIdentifiers());
        assertTrue(md.storesUpperCaseIdentifiers());
        assertFalse(md.storesLowerCaseIdentifiers());
        assertFalse(md.storesMixedCaseIdentifiers());
        assertTrue(md.supportsMixedCaseQuotedIdentifiers());
        assertTrue(md.storesMixedCaseQuotedIdentifiers());
        assertTrue(md.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertTrue(md.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));
        assertFalse(md.supportsResultSetType(ResultSet.TYPE_SCROLL_SENSITIVE));
        assertTrue(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY));
        assertFalse(md.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertTrue(md.supportsResultSetConcurrency(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertFalse(md.supportsResultSetConcurrency(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY));
        assertTrue(md.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT));
        assertFalse(md.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, md.getResultSetHoldability());
        assertFalse(md.supportsGetGeneratedKeys());
        assertTrue(md.supportsSavepoints());
        assertFalse(md.supportsNamedParameters());
        assertFalse(md.supportsMultipleResultSets());
        assertFalse(md.supportsStoredProcedures());
        assertTrue(md.usesLocalFiles());
        assertFalse(md.locatorsUpdateCopy());
        assertFalse(md.generatedKeyAlwaysReturned());
        assertFalse(md.autoCommitFailureClosesAllResultSets());
        DriverTest.assertWrapper(md, VsqlDatabaseMetaData.class);
    }

    @Test
    void productVersion() {
        assertEquals("1.7.58", VsqlDatabaseMetaData.productVersion("v1.7.58", "01.07.0058"));
        assertEquals("1.7.58", VsqlDatabaseMetaData.productVersion("abc123", "01.07.0058"));
    }

    @Test
    void resultSet() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES (CAST(1 AS INTEGER), CAST('a' AS VARCHAR(1)))")) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals("07009", assertThrows(SQLException.class, () -> md.getColumnName(0)).getSQLState());
            assertEquals("07009", assertThrows(SQLException.class, () -> md.getColumnName(3)).getSQLState());
            assertEquals(12, md.getColumnDisplaySize(1));
            assertEquals(5, md.getColumnDisplaySize(2));
            assertFalse(md.isCaseSensitive(1));
            assertTrue(md.isCaseSensitive(2));
            assertFalse(md.isAutoIncrement(1));
            assertTrue(md.isSearchable(1));
            assertFalse(md.isCurrency(1));
            assertFalse(md.isReadOnly(1));
            assertTrue(md.isWritable(1));
            assertFalse(md.isDefinitelyWritable(1));
            assertEquals("", md.getSchemaName(1));
            assertEquals("", md.getTableName(1));
            assertEquals("", md.getCatalogName(1));
            DriverTest.assertWrapper(md, VsqlResultSetMetaData.class);
        }
    }

    @Test
    void parameters() throws SQLException {
        ParameterMetaData md = new VsqlParameterMetaData(new Column[] {
            new Column("", Native.SQL_INTEGER, 0, (short) 0, true),
            new Column("", Native.SQL_INTEGER, 0, (short) 0, false),
        });
        assertEquals(ParameterMetaData.parameterNullable, md.isNullable(1));
        assertEquals(ParameterMetaData.parameterNoNulls, md.isNullable(2));
        assertEquals("07009", assertThrows(SQLException.class, () -> md.isNullable(0)).getSQLState());
        assertEquals("07009", assertThrows(SQLException.class, () -> md.isNullable(3)).getSQLState());
        DriverTest.assertWrapper(md, VsqlParameterMetaData.class);
    }
}

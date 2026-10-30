package io.github.elliotchance.vsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

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

    /** The vsql release that libvsql is from, such as "1.2.35". */
    @Override
    public String getDatabaseProductVersion() {
        return Version.LIBVSQL;
    }

    @Override
    public int getDatabaseMajorVersion() {
        return Version.part(Version.LIBVSQL, 0);
    }

    @Override
    public int getDatabaseMinorVersion() {
        return Version.part(Version.LIBVSQL, 1);
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

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_READ_UNCOMMITTED || level == Connection.TRANSACTION_READ_COMMITTED;
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
        return false;
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

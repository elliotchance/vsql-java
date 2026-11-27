package io.github.elliotchance.vsql;

import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * A savepoint, see {@link VsqlConnection#setSavepoint()}. Its name in SQL is
 * made by the driver (sqlName), so any name can be given.
 */
final class VsqlSavepoint implements Savepoint {
    final VsqlConnection connection;
    final String sqlName;
    private final int id;
    private final String name;

    VsqlSavepoint(VsqlConnection connection, int id, String name) {
        this.connection = connection;
        this.id = id;
        this.name = name;
        this.sqlName = "JDBC_SAVEPOINT_" + id;
    }

    @Override
    public int getSavepointId() throws SQLException {
        if (name != null) {
            throw new SQLException("vsql: a named savepoint doesn't have an id", "HY024");
        }
        return id;
    }

    @Override
    public String getSavepointName() throws SQLException {
        if (name == null) {
            throw new SQLException("vsql: an unnamed savepoint doesn't have a name", "HY024");
        }
        return name;
    }

    @Override
    public String toString() {
        return name == null ? sqlName : name;
    }
}

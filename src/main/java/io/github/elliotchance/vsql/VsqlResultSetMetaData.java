package io.github.elliotchance.vsql;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** The columns of a result set, see {@link java.sql.ResultSet#getMetaData()}. */
public final class VsqlResultSetMetaData extends UnsupportedResultSetMetaData {
    private final Column[] columns;

    VsqlResultSetMetaData(Column[] columns) {
        this.columns = columns;
    }

    private Column column(int index) throws SQLException {
        if (index < 1 || index > columns.length) {
            throw new SQLException("vsql: invalid column index " + index, "07009");
        }
        return columns[index - 1];
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    /** The name of the column. Names that weren't quoted are upper case. */
    @Override
    public String getColumnName(int index) throws SQLException {
        return column(index).name();
    }

    @Override
    public String getColumnLabel(int index) throws SQLException {
        return column(index).name();
    }

    /** The java.sql.Types code, such as Types.VARCHAR. */
    @Override
    public int getColumnType(int index) throws SQLException {
        return column(index).jdbcType();
    }

    /** The SQL name of the type, such as "CHARACTER VARYING". */
    @Override
    public String getColumnTypeName(int index) throws SQLException {
        return column(index).typeName();
    }

    /** The class of ResultSet.getObject's value. */
    @Override
    public String getColumnClassName(int index) throws SQLException {
        return column(index).className();
    }

    @Override
    public int isNullable(int index) throws SQLException {
        return column(index).nullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
    }

    /**
     * The precision of a number, the maximum number of characters of a string,
     * or the length of a date or time.
     */
    @Override
    public int getPrecision(int index) throws SQLException {
        return column(index).precision();
    }

    /** The scale of a DECIMAL or NUMERIC, or the fractional seconds of a time. */
    @Override
    public int getScale(int index) throws SQLException {
        return column(index).scale();
    }

    @Override
    public int getColumnDisplaySize(int index) throws SQLException {
        Column c = column(index);
        return c.isNumber() ? c.precision() + 2 : Math.max(c.precision(), 5);
    }

    @Override
    public boolean isSigned(int index) throws SQLException {
        return column(index).isNumber();
    }

    @Override
    public boolean isCaseSensitive(int index) throws SQLException {
        return column(index).isCharacter();
    }

    @Override
    public boolean isAutoIncrement(int index) throws SQLException {
        column(index);
        return false;
    }

    @Override
    public boolean isSearchable(int index) throws SQLException {
        column(index);
        return true;
    }

    @Override
    public boolean isCurrency(int index) throws SQLException {
        column(index);
        return false;
    }

    @Override
    public boolean isReadOnly(int index) throws SQLException {
        column(index);
        return false;
    }

    @Override
    public boolean isWritable(int index) throws SQLException {
        column(index);
        return true;
    }

    @Override
    public boolean isDefinitelyWritable(int index) throws SQLException {
        column(index);
        return false;
    }

    @Override
    public String getSchemaName(int index) throws SQLException {
        column(index);
        return "";
    }

    @Override
    public String getTableName(int index) throws SQLException {
        column(index);
        return "";
    }

    @Override
    public String getCatalogName(int index) throws SQLException {
        column(index);
        return "";
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

package io.github.elliotchance.vsql;

import java.sql.ParameterMetaData;
import java.sql.SQLException;

/**
 * The parameters of a prepared statement, see
 * {@link java.sql.PreparedStatement#getParameterMetaData()}. vsql determines
 * the type of each parameter from where it's used (such as the type of x in
 * "x = ?"), so it's the type that a value is cast to.
 */
public final class VsqlParameterMetaData implements ParameterMetaData {
    private final Column[] params;

    VsqlParameterMetaData(Column[] params) {
        this.params = params;
    }

    private Column param(int index) throws SQLException {
        if (index < 1 || index > params.length) {
            throw Errors.sql("07009", "vsql: invalid parameter index " + index);
        }
        return params[index - 1];
    }

    @Override
    public int getParameterCount() {
        return params.length;
    }

    @Override
    public int isNullable(int index) throws SQLException {
        return param(index).nullable() ? parameterNullable : parameterNoNulls;
    }

    @Override
    public boolean isSigned(int index) throws SQLException {
        return param(index).isNumber();
    }

    /** The same as ResultSetMetaData.getPrecision. */
    @Override
    public int getPrecision(int index) throws SQLException {
        return param(index).precision();
    }

    /** The same as ResultSetMetaData.getScale. */
    @Override
    public int getScale(int index) throws SQLException {
        return param(index).scale();
    }

    /** The java.sql.Types code, such as Types.VARCHAR. */
    @Override
    public int getParameterType(int index) throws SQLException {
        return param(index).jdbcType();
    }

    /** The SQL name of the type, such as "CHARACTER VARYING". */
    @Override
    public String getParameterTypeName(int index) throws SQLException {
        return param(index).typeName();
    }

    /** The class of ResultSet.getObject's value for the type. */
    @Override
    public String getParameterClassName(int index) throws SQLException {
        return param(index).className();
    }

    /** Parameters are only IN. */
    @Override
    public int getParameterMode(int index) throws SQLException {
        param(index);
        return parameterModeIn;
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

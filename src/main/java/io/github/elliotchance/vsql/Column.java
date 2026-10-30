package io.github.elliotchance.vsql;

import java.sql.Types;

/** A column of a cursor, from SQLDescribeCol. */
record Column(String name, short dataType, int size, short digits, boolean nullable) {
    /** How a column is read with SQLGetData. */
    enum Kind {
        INTEGER,
        DOUBLE,
        REAL,
        TEXT,
    }

    Kind kind() {
        return switch (dataType) {
            case Native.SQL_SMALLINT, Native.SQL_INTEGER, Native.SQL_BIGINT -> Kind.INTEGER;
            case Native.SQL_REAL -> Kind.REAL;
            case Native.SQL_FLOAT, Native.SQL_DOUBLE -> Kind.DOUBLE;
            default -> Kind.TEXT;
        };
    }

    /** The java.sql.Types code. */
    int jdbcType() {
        return switch (dataType) {
            case Native.SQL_SMALLINT -> Types.SMALLINT;
            case Native.SQL_INTEGER -> Types.INTEGER;
            case Native.SQL_BIGINT -> Types.BIGINT;
            case Native.SQL_REAL -> Types.REAL;
            case Native.SQL_FLOAT, Native.SQL_DOUBLE -> Types.DOUBLE;
            case Native.SQL_DECIMAL -> Types.DECIMAL;
            case Native.SQL_NUMERIC -> Types.NUMERIC;
            case Native.SQL_CHAR -> Types.CHAR;
            case Native.SQL_VARCHAR -> Types.VARCHAR;
            case Native.SQL_BOOLEAN -> Types.BOOLEAN;
            case Native.SQL_TYPE_DATE -> Types.DATE;
            case Native.SQL_TYPE_TIME -> Types.TIME;
            case Native.SQL_TYPE_TIME_WITH_TIMEZONE -> Types.TIME_WITH_TIMEZONE;
            case Native.SQL_TYPE_TIMESTAMP -> Types.TIMESTAMP;
            case Native.SQL_TYPE_TIMESTAMP_WITH_TIMEZONE -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> Types.OTHER;
        };
    }

    /** The SQL name of the type, such as "CHARACTER VARYING". */
    String typeName() {
        return switch (dataType) {
            case Native.SQL_SMALLINT -> "SMALLINT";
            case Native.SQL_INTEGER -> "INTEGER";
            case Native.SQL_BIGINT -> "BIGINT";
            case Native.SQL_REAL -> "REAL";
            case Native.SQL_FLOAT, Native.SQL_DOUBLE -> "DOUBLE PRECISION";
            case Native.SQL_DECIMAL -> "DECIMAL";
            case Native.SQL_NUMERIC -> "NUMERIC";
            case Native.SQL_CHAR -> "CHARACTER";
            case Native.SQL_VARCHAR -> "CHARACTER VARYING";
            case Native.SQL_BOOLEAN -> "BOOLEAN";
            case Native.SQL_TYPE_DATE -> "DATE";
            case Native.SQL_TYPE_TIME -> "TIME";
            case Native.SQL_TYPE_TIME_WITH_TIMEZONE -> "TIME WITH TIME ZONE";
            case Native.SQL_TYPE_TIMESTAMP -> "TIMESTAMP";
            case Native.SQL_TYPE_TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP WITH TIME ZONE";
            default -> "UNKNOWN";
        };
    }

    /** The class of getObject's value. */
    String className() {
        return switch (dataType) {
            case Native.SQL_SMALLINT, Native.SQL_INTEGER -> Integer.class.getName();
            case Native.SQL_BIGINT -> Long.class.getName();
            case Native.SQL_REAL -> Float.class.getName();
            case Native.SQL_FLOAT, Native.SQL_DOUBLE -> Double.class.getName();
            case Native.SQL_DECIMAL, Native.SQL_NUMERIC -> java.math.BigDecimal.class.getName();
            case Native.SQL_BOOLEAN -> Boolean.class.getName();
            case Native.SQL_TYPE_DATE -> java.sql.Date.class.getName();
            case Native.SQL_TYPE_TIME -> java.sql.Time.class.getName();
            case Native.SQL_TYPE_TIME_WITH_TIMEZONE -> java.time.OffsetTime.class.getName();
            case Native.SQL_TYPE_TIMESTAMP -> java.sql.Timestamp.class.getName();
            case Native.SQL_TYPE_TIMESTAMP_WITH_TIMEZONE -> java.time.OffsetDateTime.class.getName();
            default -> String.class.getName();
        };
    }

    boolean isCharacter() {
        return dataType == Native.SQL_CHAR || dataType == Native.SQL_VARCHAR;
    }

    boolean isNumber() {
        return switch (dataType) {
            case Native.SQL_SMALLINT, Native.SQL_INTEGER, Native.SQL_BIGINT, Native.SQL_REAL,
                    Native.SQL_FLOAT, Native.SQL_DOUBLE, Native.SQL_DECIMAL, Native.SQL_NUMERIC -> true;
            default -> false;
        };
    }

    /** The precision (see ResultSetMetaData.getPrecision). */
    int precision() {
        return switch (dataType) {
            case Native.SQL_SMALLINT -> 5;
            case Native.SQL_INTEGER -> 10;
            case Native.SQL_BIGINT -> 19;
            case Native.SQL_REAL -> 7;
            case Native.SQL_FLOAT, Native.SQL_DOUBLE -> 15;
            case Native.SQL_BOOLEAN -> 1;
            // The size is in octets, with 4 for each character.
            case Native.SQL_CHAR, Native.SQL_VARCHAR -> Math.max(size / 4, 0);
            default -> size;
        };
    }

    /** The scale of a DECIMAL or NUMERIC, or the fractional seconds of a time. */
    int scale() {
        return switch (dataType) {
            case Native.SQL_DECIMAL, Native.SQL_NUMERIC, Native.SQL_TYPE_TIME, Native.SQL_TYPE_TIMESTAMP,
                    Native.SQL_TYPE_TIME_WITH_TIMEZONE, Native.SQL_TYPE_TIMESTAMP_WITH_TIMEZONE -> digits;
            default -> 0;
        };
    }
}

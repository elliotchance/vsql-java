package io.github.elliotchance.vsql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Targets converts the parameters of setObject and setNull that have a
 * java.sql.Types code (the target type) to the Params that Native.execute
 * binds: the code is the ParameterType of SQLBindParameter, and the value is
 * converted to a C value that libvsql can cast to it (usually its string).
 */
final class Targets {
    private Targets() {
    }

    // The maximum precision of a NUMERIC or DECIMAL in vsql.
    private static final int MAX_PRECISION = 1000;

    /**
     * The parameter for a value of a target type. A scale or length (which
     * can be null) is the scale of a DECIMAL or NUMERIC, or the length of a
     * character or binary string. Types.NULL and Types.OTHER (the type of an
     * interval) send the value as its Java type, see Values.param.
     */
    static Param param(Object x, int targetSqlType, Integer scaleOrLength) throws SQLException {
        if (targetSqlType == Types.NULL || targetSqlType == Types.OTHER) {
            return Values.param(x);
        }
        short type = parameterType(targetSqlType);
        if (x == null) {
            return nullParam(type, scaleOrLength);
        }

        Param p = switch (type) {
            case Native.SQL_BOOLEAN -> bool(x);
            case Native.SQL_SMALLINT, Native.SQL_INTEGER, Native.SQL_BIGINT, Native.SQL_REAL, Native.SQL_DOUBLE ->
                    number(x, type);
            case Native.SQL_DECIMAL, Native.SQL_NUMERIC -> decimal(x, type, scaleOrLength);
            case Native.SQL_CHAR, Native.SQL_VARCHAR, Native.SQL_CLOB -> character(x, type, scaleOrLength);
            case Native.SQL_BINARY, Native.SQL_VARBINARY, Native.SQL_BLOB -> binary(x, type, scaleOrLength);
            default -> datetime(x, type);
        };
        if (p == null) {
            throw new SQLException("vsql: a " + x.getClass().getName() + " can't be sent as "
                    + JDBCType.valueOf(targetSqlType).getName(), "07006");
        }
        return p;
    }

    /** The ParameterType for a java.sql.Types code. */
    private static short parameterType(int targetSqlType) throws SQLException {
        return switch (targetSqlType) {
            case Types.BIT, Types.BOOLEAN -> Native.SQL_BOOLEAN;
            case Types.TINYINT, Types.SMALLINT -> Native.SQL_SMALLINT;
            case Types.INTEGER -> Native.SQL_INTEGER;
            case Types.BIGINT -> Native.SQL_BIGINT;
            case Types.REAL -> Native.SQL_REAL;
            case Types.FLOAT, Types.DOUBLE -> Native.SQL_DOUBLE;
            case Types.DECIMAL -> Native.SQL_DECIMAL;
            case Types.NUMERIC -> Native.SQL_NUMERIC;
            case Types.CHAR, Types.NCHAR -> Native.SQL_CHAR;
            case Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> Native.SQL_VARCHAR;
            case Types.CLOB -> Native.SQL_CLOB;
            case Types.BINARY -> Native.SQL_BINARY;
            case Types.VARBINARY, Types.LONGVARBINARY -> Native.SQL_VARBINARY;
            case Types.BLOB -> Native.SQL_BLOB;
            case Types.DATE -> Native.SQL_TYPE_DATE;
            case Types.TIME -> Native.SQL_TYPE_TIME;
            case Types.TIME_WITH_TIMEZONE -> Native.SQL_TYPE_TIME_WITH_TIMEZONE;
            case Types.TIMESTAMP -> Native.SQL_TYPE_TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE -> Native.SQL_TYPE_TIMESTAMP_WITH_TIMEZONE;
            default -> throw Errors.unsupported(typeName(targetSqlType));
        };
    }

    private static String typeName(int targetSqlType) {
        try {
            return "the type " + JDBCType.valueOf(targetSqlType).getName();
        } catch (IllegalArgumentException e) {
            return "the java.sql.Types code " + targetSqlType;
        }
    }

    /** The scale of a DECIMAL or NUMERIC, if there is one. */
    private static Integer scale(Integer scaleOrLength) throws SQLException {
        if (scaleOrLength != null && (scaleOrLength < 0 || scaleOrLength > MAX_PRECISION)) {
            throw new SQLException("vsql: the scale must be from 0 to " + MAX_PRECISION + ", not "
                    + scaleOrLength, "HY104");
        }
        return scaleOrLength;
    }

    /** The length of a string, or the default if there isn't one. */
    private static int length(Integer scaleOrLength, int otherwise) {
        return scaleOrLength != null && scaleOrLength > 0 ? scaleOrLength : Math.max(otherwise, 1);
    }

    private static Param nullParam(short type, Integer scaleOrLength) throws SQLException {
        return switch (type) {
            case Native.SQL_CHAR, Native.SQL_VARCHAR, Native.SQL_CLOB ->
                    new Param(Native.SQL_CHAR, type, length(scaleOrLength, 1), (short) 0, null);
            case Native.SQL_BINARY, Native.SQL_VARBINARY, Native.SQL_BLOB ->
                    new Param(Native.SQL_BINARY, type, length(scaleOrLength, 1), (short) 0, null);
            case Native.SQL_DECIMAL, Native.SQL_NUMERIC -> {
                Integer scale = scale(scaleOrLength);
                yield new Param(Native.SQL_CHAR, type, MAX_PRECISION, (short) (scale == null ? 0 : scale), null);
            }
            // The length of each datetime's string (see Values), with 6
            // fractional digits.
            case Native.SQL_TYPE_DATE -> new Param(Native.SQL_CHAR, type, 10, (short) 0, null);
            case Native.SQL_TYPE_TIME -> new Param(Native.SQL_CHAR, type, 15, (short) 6, null);
            case Native.SQL_TYPE_TIME_WITH_TIMEZONE -> new Param(Native.SQL_CHAR, type, 21, (short) 6, null);
            case Native.SQL_TYPE_TIMESTAMP -> new Param(Native.SQL_CHAR, type, 26, (short) 6, null);
            case Native.SQL_TYPE_TIMESTAMP_WITH_TIMEZONE -> new Param(Native.SQL_CHAR, type, 32, (short) 6, null);
            default -> new Param(Native.SQL_CHAR, type, 0, (short) 0, null);
        };
    }

    /** A parameter with the same value as another, but another type. */
    private static Param retype(Param p, short type, int columnSize, int decimalDigits) {
        return new Param(p.valueType(), type, columnSize, (short) decimalDigits, p.value());
    }

    private static Param text(short type, int columnSize, int decimalDigits, String s) {
        return new Param(Native.SQL_CHAR, type, columnSize, (short) decimalDigits, s);
    }

    /** A number is TRUE if it isn't zero, and a string is cast by vsql. */
    private static Param bool(Object x) throws SQLException {
        return switch (x) {
            case Boolean b -> Values.param(b);
            case BigDecimal d -> Values.param(d.signum() != 0);
            case BigInteger i -> Values.param(i.signum() != 0);
            case Float f -> Values.param(f != 0);
            case Double d -> Values.param(d != 0);
            case Byte b -> Values.param(b != 0);
            case Short s -> Values.param(s != 0);
            case Integer i -> Values.param(i != 0);
            case Long l -> Values.param(l != 0);
            case String s -> text(Native.SQL_BOOLEAN, 0, 0, s);
            case Character c -> text(Native.SQL_BOOLEAN, 0, 0, c.toString());
            default -> null;
        };
    }

    /** A BOOLEAN is 1 or 0, and a string is cast by vsql. */
    private static Param number(Object x, short type) throws SQLException {
        return switch (x) {
            case Boolean b -> new Param(Native.SQL_BIGINT, type, 0, (short) 0, b ? 1L : 0L);
            case Byte b -> retype(Values.param(b), type, 0, 0);
            case Short s -> retype(Values.param(s), type, 0, 0);
            case Integer i -> retype(Values.param(i), type, 0, 0);
            case Long l -> retype(Values.param(l), type, 0, 0);
            case BigInteger i -> retype(Values.param(i), type, 0, 0);
            case Float f -> retype(Values.param(f), type, 0, 0);
            case Double d -> retype(Values.param(d), type, 0, 0);
            case BigDecimal d -> retype(Values.param(d), type, 0, 0);
            case String s -> text(type, 0, 0, s);
            case Character c -> text(type, 0, 0, c.toString());
            default -> null;
        };
    }

    /**
     * A DECIMAL or NUMERIC with the precision of its digits, rounded half up
     * to the scale, if there is one.
     */
    private static Param decimal(Object x, short type, Integer scaleOrLength) throws SQLException {
        Integer scale = scale(scaleOrLength);
        BigDecimal d;
        try {
            d = switch (x) {
                case BigDecimal v -> v;
                case BigInteger v -> new BigDecimal(v);
                case Byte v -> BigDecimal.valueOf(v);
                case Short v -> BigDecimal.valueOf(v);
                case Integer v -> BigDecimal.valueOf(v);
                case Long v -> BigDecimal.valueOf(v);
                case Float v -> new BigDecimal(v.toString());
                case Double v -> BigDecimal.valueOf(v);
                case Boolean v -> v ? BigDecimal.ONE : BigDecimal.ZERO;
                case String v -> new BigDecimal(v.trim());
                case Character v -> new BigDecimal(v.toString());
                default -> null;
            };
        } catch (NumberFormatException e) {
            throw new SQLException("vsql: " + x + " isn't a valid "
                    + (type == Native.SQL_DECIMAL ? "DECIMAL" : "NUMERIC"), "22018", e);
        }
        if (d == null) {
            return null;
        }
        if (scale != null) {
            d = d.setScale(scale, RoundingMode.HALF_UP);
        }
        Param p = Values.numeric(d);
        return retype(p, type, p.columnSize(), p.decimalDigits());
    }

    /**
     * A character string, which is the string of the value (such as
     * "2022-01-02" for a date). Its length is the scale or length, otherwise
     * the length of the string.
     */
    private static Param character(Object x, short type, Integer scaleOrLength) throws SQLException {
        String s = switch (x) {
            case String v -> v;
            case Character v -> v.toString();
            case java.sql.Clob v -> Values.string(v);
            case BigDecimal v -> v.toPlainString();
            case Number v -> v.toString();
            case byte[] v -> null;
            case java.sql.Blob v -> null;
            default -> Values.param(x).value() instanceof String v ? v : null;
        };
        if (s == null) {
            return null;
        }
        return text(type, length(scaleOrLength, s.codePointCount(0, s.length())), 0, s);
    }

    /** A binary string, whose length is the scale or length, otherwise its own. */
    private static Param binary(Object x, short type, Integer scaleOrLength) throws SQLException {
        byte[] b = switch (x) {
            case byte[] v -> v;
            case java.sql.Blob v -> Values.bytes(v);
            default -> null;
        };
        if (b == null) {
            return null;
        }
        return new Param(Native.SQL_BINARY, type, length(scaleOrLength, b.length), (short) 0, b);
    }

    /**
     * A datetime. A string is cast by vsql, and a Java datetime is converted
     * to the target type first (such as the date of a timestamp). A local
     * datetime sent as a WITH TIME ZONE type is given the session time zone by
     * vsql.
     */
    private static Param datetime(Object x, short type) throws SQLException {
        if (x instanceof String || x instanceof Character) {
            String s = x.toString();
            return text(type, Math.max(s.length(), 1), type == Native.SQL_TYPE_DATE ? 0 : 6, s);
        }
        Object v = switch (type) {
            case Native.SQL_TYPE_DATE -> date(x);
            case Native.SQL_TYPE_TIME -> time(x);
            case Native.SQL_TYPE_TIME_WITH_TIMEZONE -> timeWithTimeZone(x);
            case Native.SQL_TYPE_TIMESTAMP -> timestamp(x);
            default -> timestampWithTimeZone(x);
        };
        if (v == null) {
            return null;
        }
        Param p = Values.param(v);
        return retype(p, type, p.columnSize(), p.decimalDigits());
    }

    /** A date or timestamp as a LocalDateTime (a date is at midnight), or null. */
    private static LocalDateTime localDateTime(Object x) {
        return switch (x) {
            case java.sql.Timestamp v -> v.toLocalDateTime();
            case java.sql.Date v -> v.toLocalDate().atStartOfDay();
            case java.sql.Time v -> null;
            case java.util.Date v -> new java.sql.Timestamp(v.getTime()).toLocalDateTime();
            case LocalDateTime v -> v;
            case LocalDate v -> v.atStartOfDay();
            case OffsetDateTime v -> v.toLocalDateTime();
            case ZonedDateTime v -> v.toLocalDateTime();
            case Instant v -> LocalDateTime.ofInstant(v, ZoneOffset.UTC);
            default -> null;
        };
    }

    private static Object date(Object x) {
        if (x instanceof java.sql.Date || x instanceof LocalDate) {
            return x;
        }
        LocalDateTime t = localDateTime(x);
        return t == null ? null : t.toLocalDate();
    }

    private static Object time(Object x) {
        return switch (x) {
            case java.sql.Time v -> v;
            case LocalTime v -> v;
            case OffsetTime v -> v.toLocalTime();
            case java.sql.Date v -> null;
            case LocalDate v -> null;
            default -> {
                LocalDateTime t = localDateTime(x);
                yield t == null ? null : t.toLocalTime();
            }
        };
    }

    private static Object timeWithTimeZone(Object x) {
        return switch (x) {
            case OffsetTime v -> v;
            case OffsetDateTime v -> v.toOffsetTime();
            case ZonedDateTime v -> v.toOffsetDateTime().toOffsetTime();
            case Instant v -> OffsetTime.ofInstant(v, ZoneOffset.UTC);
            default -> time(x);
        };
    }

    private static Object timestamp(Object x) {
        if (x instanceof java.sql.Timestamp || x instanceof LocalDateTime) {
            return x;
        }
        return localDateTime(x);
    }

    private static Object timestampWithTimeZone(Object x) {
        if (x instanceof OffsetDateTime || x instanceof ZonedDateTime || x instanceof Instant) {
            return x;
        }
        return localDateTime(x);
    }
}

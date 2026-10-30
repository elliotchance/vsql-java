package io.github.elliotchance.vsql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;

/**
 * Values converts parameters to the Params that Native.execute binds, and
 * parses the strings that columns are read as.
 */
final class Values {
    private Values() {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /**
     * The parameter for a value. Each Java type is sent as an SQL type, which
     * is cast to the type it's used as (see the package documentation).
     */
    static Param param(Object x) throws SQLException {
        return switch (x) {
            // The type doesn't matter for a NULL, since it's cast to the type
            // it's used as.
            case null -> new Param(Native.SQL_CHAR, Native.SQL_VARCHAR, 1, (short) 0, null);
            case Boolean b -> text(Native.SQL_BOOLEAN, 0, 0, b ? "TRUE" : "FALSE");
            case Byte b -> bigint(b);
            case Short s -> bigint(s);
            case Integer i -> bigint(i);
            case Long l -> bigint(l);
            case BigInteger i -> i.bitLength() < 64 ? bigint(i.longValue()) : numeric(new BigDecimal(i));
            case Float f -> new Param(Native.SQL_DOUBLE, Native.SQL_DOUBLE, 0, (short) 0, (double) f);
            case Double d -> new Param(Native.SQL_DOUBLE, Native.SQL_DOUBLE, 0, (short) 0, d);
            case BigDecimal d -> numeric(d);
            case String s -> varchar(s);
            case Character c -> varchar(c.toString());
            // java.sql's types are local times (in the JVM's time zone).
            case java.sql.Date d -> date(d.toLocalDate());
            case java.sql.Time t -> time(t.toLocalTime(), null);
            case java.sql.Timestamp t -> timestamp(t.toLocalDateTime(), null);
            case java.util.Date d -> timestamp(new java.sql.Timestamp(d.getTime()).toLocalDateTime(), null);
            case LocalDate d -> date(d);
            case LocalTime t -> time(t, null);
            case LocalDateTime t -> timestamp(t, null);
            case OffsetTime t -> time(t.toLocalTime(), t.getOffset());
            case OffsetDateTime t -> timestamp(t.toLocalDateTime(), t.getOffset());
            case ZonedDateTime t -> timestamp(t.toLocalDateTime(), t.getOffset());
            case Instant i -> timestamp(LocalDateTime.ofInstant(i, ZoneOffset.UTC), ZoneOffset.UTC);
            case byte[] b -> throw Errors.unsupported("a byte[] parameter (vsql doesn't have a binary type)");
            default -> throw new SQLException(
                    "vsql: a " + x.getClass().getName() + " can't be a parameter", "07006");
        };
    }

    private static Param bigint(long l) {
        return new Param(Native.SQL_BIGINT, Native.SQL_BIGINT, 0, (short) 0, l);
    }

    private static Param text(short parameterType, int columnSize, int decimalDigits, String s) {
        return new Param(Native.SQL_CHAR, parameterType, columnSize, (short) decimalDigits, s);
    }

    /** A CHARACTER VARYING as long as the string (which can't be 0). */
    private static Param varchar(String s) {
        return text(Native.SQL_VARCHAR, Math.max(s.codePointCount(0, s.length()), 1), 0, s);
    }

    /**
     * A NUMERIC with the precision of the digits. vsql counts at least one
     * digit before the decimal point (so 0.25 is NUMERIC(3, 2)).
     */
    static Param numeric(BigDecimal d) throws SQLException {
        String s = d.toPlainString();
        int scale = Math.max(d.scale(), 0);
        String unsigned = s.startsWith("-") ? s.substring(1) : s;
        int point = unsigned.indexOf('.');
        String integer = point < 0 ? unsigned : unsigned.substring(0, point);
        int integerDigits = Math.max(integer.replaceFirst("^0+", "").length(), 1);
        if (scale > Short.MAX_VALUE) {
            throw new SQLException("vsql: " + s + " has too many digits", "22003");
        }

        return text(Native.SQL_NUMERIC, integerDigits + scale, scale, s);
    }

    private static Param date(LocalDate d) {
        String s = d.format(DATE);
        return text(Native.SQL_TYPE_DATE, s.length(), 0, s);
    }

    private static Param time(LocalTime t, ZoneOffset offset) throws SQLException {
        String s = t.format(TIME) + offset(offset);
        short type = offset == null ? Native.SQL_TYPE_TIME : Native.SQL_TYPE_TIME_WITH_TIMEZONE;
        return text(type, s.length(), 6, s);
    }

    private static Param timestamp(LocalDateTime t, ZoneOffset offset) throws SQLException {
        String s = t.toLocalDate().format(DATE) + " " + t.toLocalTime().format(TIME) + offset(offset);
        short type = offset == null ? Native.SQL_TYPE_TIMESTAMP : Native.SQL_TYPE_TIMESTAMP_WITH_TIMEZONE;
        return text(type, s.length(), 6, s);
    }

    /** The offset as "+HH:MM", or "" for null. */
    private static String offset(ZoneOffset offset) throws SQLException {
        if (offset == null) {
            return "";
        }
        int seconds = offset.getTotalSeconds();
        if (seconds % 60 != 0) {
            throw new SQLException("vsql: the offset " + offset + " isn't a whole number of minutes", "22009");
        }
        int minutes = Math.abs(seconds / 60);

        return String.format("%s%02d:%02d", seconds < 0 ? "-" : "+", minutes / 60, minutes % 60);
    }

    // The datetimes from SQLGetData, such as "2022-01-02T15:04:05.123456+0530".
    // There are at most 6 fractional digits.
    private static final DateTimeFormatter TIME_COLUMN = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, true).optionalEnd()
            .optionalStart().appendOffset("+HHMM", "+0000").optionalEnd()
            .toFormatter();
    private static final DateTimeFormatter TIMESTAMP_COLUMN = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .append(TIME_COLUMN)
            .toFormatter();

    private static TemporalAccessor parse(DateTimeFormatter formatter, String s, String type) throws SQLException {
        try {
            return formatter.parse(s);
        } catch (DateTimeParseException e) {
            throw new SQLException("vsql: invalid " + type + ": " + s, "22007", e);
        }
    }

    static LocalDate parseDate(String s) throws SQLException {
        return LocalDate.from(parse(DateTimeFormatter.ISO_LOCAL_DATE, s, "DATE"));
    }

    static LocalTime parseTime(String s) throws SQLException {
        return LocalTime.from(parse(TIME_COLUMN, s, "TIME"));
    }

    static OffsetTime parseTimeWithTimeZone(String s) throws SQLException {
        return OffsetTime.from(parse(TIME_COLUMN, s, "TIME WITH TIME ZONE"));
    }

    static LocalDateTime parseTimestamp(String s) throws SQLException {
        return LocalDateTime.from(parse(TIMESTAMP_COLUMN, s, "TIMESTAMP"));
    }

    static OffsetDateTime parseTimestampWithTimeZone(String s) throws SQLException {
        return OffsetDateTime.from(parse(TIMESTAMP_COLUMN, s, "TIMESTAMP WITH TIME ZONE"));
    }
}

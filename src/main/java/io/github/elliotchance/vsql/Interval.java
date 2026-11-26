package io.github.elliotchance.vsql;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Period;

/**
 * Interval converts between intervals and Periods (a year-month interval) or
 * Durations (a day-time interval, where a day is 24 hours, as it is in SQL).
 *
 * <p>An interval is retrieved as its literal, such as
 * "INTERVAL '-1 02:03:04.500000' DAY(2) TO SECOND(6)", and bound as the string
 * in the literal ("-1 02:03:04.500000").
 */
final class Interval {
    private Interval() {
    }

    // The fields of a day-time interval, as seconds.
    private static final long[] SECONDS = {86_400, 3_600, 60, 1};

    /** The fields of an interval, such as "DAY TO SECOND". */
    static String qualifier(short type) {
        return switch (type) {
            case Native.SQL_INTERVAL_YEAR -> "YEAR";
            case Native.SQL_INTERVAL_MONTH -> "MONTH";
            case Native.SQL_INTERVAL_DAY -> "DAY";
            case Native.SQL_INTERVAL_HOUR -> "HOUR";
            case Native.SQL_INTERVAL_MINUTE -> "MINUTE";
            case Native.SQL_INTERVAL_SECOND -> "SECOND";
            case Native.SQL_INTERVAL_YEAR_TO_MONTH -> "YEAR TO MONTH";
            case Native.SQL_INTERVAL_DAY_TO_HOUR -> "DAY TO HOUR";
            case Native.SQL_INTERVAL_DAY_TO_MINUTE -> "DAY TO MINUTE";
            case Native.SQL_INTERVAL_DAY_TO_SECOND -> "DAY TO SECOND";
            case Native.SQL_INTERVAL_HOUR_TO_MINUTE -> "HOUR TO MINUTE";
            case Native.SQL_INTERVAL_HOUR_TO_SECOND -> "HOUR TO SECOND";
            case Native.SQL_INTERVAL_MINUTE_TO_SECOND -> "MINUTE TO SECOND";
            default -> throw new IllegalArgumentException("vsql: not an interval: " + type);
        };
    }

    static boolean isYearMonth(short type) {
        return type == Native.SQL_INTERVAL_YEAR || type == Native.SQL_INTERVAL_MONTH
                || type == Native.SQL_INTERVAL_YEAR_TO_MONTH;
    }

    /** The first field of a day-time interval (0 for DAY to 3 for SECOND). */
    private static int leadingField(short type) {
        return switch (type) {
            case Native.SQL_INTERVAL_DAY, Native.SQL_INTERVAL_DAY_TO_HOUR, Native.SQL_INTERVAL_DAY_TO_MINUTE,
                    Native.SQL_INTERVAL_DAY_TO_SECOND -> 0;
            case Native.SQL_INTERVAL_HOUR, Native.SQL_INTERVAL_HOUR_TO_MINUTE,
                    Native.SQL_INTERVAL_HOUR_TO_SECOND -> 1;
            case Native.SQL_INTERVAL_MINUTE, Native.SQL_INTERVAL_MINUTE_TO_SECOND -> 2;
            default -> 3;
        };
    }

    private static SQLException invalid(String s) {
        return new SQLException("vsql: invalid interval: " + s, "22006");
    }

    /** A year-month interval (the string in its literal) as a Period. */
    static Period period(short type, String s) throws SQLException {
        boolean negative = s.startsWith("-");
        String[] parts = (negative ? s.substring(1) : s).split("-");
        try {
            Period p = switch (type) {
                case Native.SQL_INTERVAL_YEAR -> Period.ofYears(Integer.parseInt(parts[0]));
                case Native.SQL_INTERVAL_MONTH -> Period.ofMonths(Integer.parseInt(parts[0]));
                default -> Period.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 0);
            };
            return negative ? p.negated() : p;
        } catch (RuntimeException e) {
            throw invalid(s);
        }
    }

    /** A day-time interval (the string in its literal) as a Duration. */
    static Duration duration(short type, String s) throws SQLException {
        boolean negative = s.startsWith("-");
        String[] parts = (negative ? s.substring(1) : s).split("[ :]");
        int field = leadingField(type);
        if (field + parts.length > SECONDS.length) {
            throw invalid(s);
        }
        try {
            Duration d = Duration.ZERO;
            for (String part : parts) {
                if (field == 3) {
                    // Seconds have a fraction of up to 6 digits.
                    BigDecimal seconds = new BigDecimal(part);
                    d = d.plusSeconds(seconds.longValue())
                            .plusNanos(seconds.remainder(BigDecimal.ONE).movePointRight(9).longValueExact());
                } else {
                    d = d.plusSeconds(Long.parseLong(part) * SECONDS[field]);
                }
                field++;
            }
            return negative ? d.negated() : d;
        } catch (RuntimeException e) {
            throw invalid(s);
        }
    }

    /** A Period as the string of an INTERVAL YEAR TO MONTH ("-1-2"). */
    static String format(Period p) throws SQLException {
        if (p.getDays() != 0) {
            throw new SQLException("vsql: a Period with days (" + p + ") can't be an interval", "22015");
        }
        long months = p.toTotalMonths();
        long abs = Math.abs(months);
        return (months < 0 ? "-" : "") + abs / 12 + "-" + abs % 12;
    }

    /**
     * A Duration as the string of an INTERVAL DAY TO SECOND
     * ("-1 02:03:04.500000"). Nanoseconds are truncated to microseconds.
     */
    static String format(Duration d) {
        boolean negative = d.isNegative();
        Duration abs = d.abs();
        long seconds = abs.getSeconds();
        return String.format("%s%d %02d:%02d:%02d.%06d", negative ? "-" : "", seconds / 86_400,
                seconds % 86_400 / 3_600, seconds % 3_600 / 60, seconds % 60, abs.getNano() / 1_000);
    }
}

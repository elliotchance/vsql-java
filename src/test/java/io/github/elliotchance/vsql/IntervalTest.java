package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Period;
import org.junit.jupiter.api.Test;

/** Converting intervals, see Interval. */
class IntervalTest {
    @Test
    void qualifiers() {
        assertEquals("HOUR", Interval.qualifier(Native.SQL_INTERVAL_HOUR));
        assertEquals("MINUTE", Interval.qualifier(Native.SQL_INTERVAL_MINUTE));
        assertEquals("DAY TO MINUTE", Interval.qualifier(Native.SQL_INTERVAL_DAY_TO_MINUTE));
        assertEquals("HOUR TO SECOND", Interval.qualifier(Native.SQL_INTERVAL_HOUR_TO_SECOND));
        assertThrows(IllegalArgumentException.class, () -> Interval.qualifier(Native.SQL_INTEGER));
    }

    @Test
    void invalid() {
        assertEquals("22006", assertThrows(SQLException.class,
                () -> Interval.period(Native.SQL_INTERVAL_YEAR, "x")).getSQLState());
        assertEquals("22006", assertThrows(SQLException.class,
                () -> Interval.duration(Native.SQL_INTERVAL_MINUTE_TO_SECOND, "1:2:3")).getSQLState());
        assertEquals("22006", assertThrows(SQLException.class,
                () -> Interval.duration(Native.SQL_INTERVAL_HOUR, "x")).getSQLState());
    }

    @Test
    void valid() throws SQLException {
        assertEquals(Period.ofYears(-2), Interval.period(Native.SQL_INTERVAL_YEAR, "-2"));
        assertEquals(Duration.ofMinutes(62), Interval.duration(Native.SQL_INTERVAL_HOUR_TO_MINUTE, "1:02"));
    }
}

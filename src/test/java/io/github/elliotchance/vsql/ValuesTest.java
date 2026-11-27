package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** The parameters for Java values, and parsing datetimes, see Values. */
class ValuesTest {
    @Test
    void params() throws SQLException {
        assertEquals(5L, Values.param(BigInteger.valueOf(5)).value());
        assertEquals(Native.SQL_NUMERIC, Values.param(BigInteger.TWO.pow(70)).parameterType());
        assertEquals("c", Values.param('c').value());
        assertEquals(Native.SQL_TYPE_TIMESTAMP, Values.param(new java.util.Date(0)).parameterType());
        assertEquals("2022-01-02 15:04:05.000000+05:30", Values.param(
                ZonedDateTime.of(2022, 1, 2, 15, 4, 5, 0, ZoneOffset.ofHoursMinutes(5, 30))).value());
    }

    @Test
    void invalidParams() {
        assertEquals("22003", assertThrows(SQLException.class,
                () -> Values.param(new BigDecimal("1E-40000"))).getSQLState());
        assertEquals("22009", assertThrows(SQLException.class,
                () -> Values.param(OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofTotalSeconds(30)))).getSQLState());
    }

    @Test
    void literals() throws SQLException {
        assertEquals("2022-01-02", Values.unliteral("DATE '2022-01-02'"));
        assertEquals("2022-01-02", Values.unliteral("2022-01-02"));
        assertEquals("x'", Values.unliteral("x'"));
        assertEquals("22007", assertThrows(SQLException.class, () -> Values.parseDate("DATE 'x'")).getSQLState());
    }
}

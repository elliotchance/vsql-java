package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimeFractionTest {
    /** A connection to a new in-memory database. */
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    void exec(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    /** 12:34:56.789 in the JVM's time zone. */
    static Time time() {
        return new Time(Time.valueOf("12:34:56").getTime() + 789);
    }

    LocalTime insertAndRead(int how) throws SQLException {
        exec("CREATE TABLE t (x TIME(6))");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO t (x) VALUES (?)")) {
            switch (how) {
                case 0 -> s.setTime(1, time());
                case 1 -> s.setObject(1, time());
                default -> s.setTime(1, time(), Calendar.getInstance());
            }
            s.executeUpdate();
        }
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM t")) {
            assertTrue(rs.next());
            return rs.getObject(1, LocalTime.class);
        }
    }

    @Test
    void setTimeKeepsMilliseconds() throws SQLException {
        assertEquals(LocalTime.of(12, 34, 56, 789_000_000), insertAndRead(0));
    }

    @Test
    void setObjectKeepsMilliseconds() throws SQLException {
        assertEquals(LocalTime.of(12, 34, 56, 789_000_000), insertAndRead(1));
    }

    @Test
    void setTimeWithCalendarAgrees() throws SQLException {
        assertEquals(LocalTime.of(12, 34, 56, 789_000_000), insertAndRead(2));
    }

    @Test
    void getTimeKeepsMilliseconds() throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES ROW(TIME '12:34:56.789', TIME '12:34:56.789123', "
                        + "TIMESTAMP '2022-01-02 12:34:56.789')")) {
            assertTrue(rs.next());
            assertEquals(time(), rs.getTime(1));
            assertEquals(789, Math.floorMod(rs.getTime(1).getTime(), 1000L));
            assertEquals(time(), assertInstanceOf(Time.class, rs.getObject(1)));
            assertEquals(time(), rs.getObject(1, Time.class));
            // Microseconds are truncated to milliseconds.
            assertEquals(time(), rs.getTime(2));
            assertEquals(time(), rs.getTime(3));
        }
    }

    @Test
    void roundTrip() throws SQLException {
        exec("CREATE TABLE t (x TIME(3))");
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO t (x) VALUES (?)")) {
            s.setTime(1, time());
            s.executeUpdate();
        }
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT x FROM t")) {
            assertTrue(rs.next());
            assertEquals(time(), rs.getTime(1));
        }
    }

    @Test
    void timeWithTimeZoneIsConvertedToTheJvmTimeZone() throws SQLException {
        OffsetTime value = OffsetTime.of(10, 0, 0, 250_000_000, ZoneOffset.ofHours(5));
        LocalDateTime local = LocalDate.EPOCH.atTime(value).atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("VALUES TIME '10:00:00.250+05:00'")) {
            assertTrue(rs.next());
            assertEquals(value, rs.getObject(1));
            assertEquals(value, rs.getObject(1, OffsetTime.class));
            assertEquals(local.toLocalTime(), rs.getObject(1, LocalTime.class));
            assertEquals(new Time(Time.valueOf(local.toLocalTime()).getTime() + 250), rs.getTime(1));
            assertEquals(Timestamp.valueOf(local), rs.getTimestamp(1));

            // 05:00:00.250 UTC on 1970-01-01.
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            assertEquals(new Time(5 * 3_600_000 + 250), rs.getTime(1, utc));
            assertEquals(new Timestamp(5 * 3_600_000 + 250), rs.getTimestamp(1, utc));
        }
    }
}

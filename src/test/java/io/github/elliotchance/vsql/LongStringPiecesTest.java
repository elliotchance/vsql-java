package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A string that's longer than 255 octets is read in pieces (see
 * Native.getString), the first piece being 255 octets.
 */
class LongStringPiecesTest {
    /** A connection to a new in-memory database. */
    Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:vsql::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE foo (s VARCHAR(2000))");
        }
    }

    @AfterEach
    void close() throws SQLException {
        conn.close();
    }

    static Stream<Arguments> strings() {
        return Stream.of(
                // U+0000 in the first piece.
                Arguments.of("nul near the start", "a\u0000" + "b".repeat(1000)),
                Arguments.of("nul at the start", "\u0000" + "b".repeat(300)),
                Arguments.of("nul at the end of the first piece", "a".repeat(254) + "\u0000" + "b".repeat(300)),
                Arguments.of("nul after the first piece", "a".repeat(255) + "\u0000" + "b".repeat(300)),
                Arguments.of("only nul", "\u0000".repeat(600)),
                Arguments.of("nul in a short string", "a\u0000b"),
                Arguments.of("nul in 255 octets", "\u0000".repeat(255)),
                Arguments.of("nul in 256 octets", "\u0000".repeat(256)),

                // Multi-byte UTF-8 characters split across the pieces.
                Arguments.of("2 octets split", "a".repeat(254) + "é" + "b".repeat(300)),
                Arguments.of("3 octets split after 1", "a".repeat(254) + "€" + "b".repeat(300)),
                Arguments.of("3 octets split after 2", "a".repeat(253) + "€" + "b".repeat(300)),
                Arguments.of("4 octets split after 1", "a".repeat(254) + "😀" + "b".repeat(300)),
                Arguments.of("4 octets split after 3", "a".repeat(252) + "😀" + "b".repeat(300)),

                // Both.
                Arguments.of("nul and split", "\u0000".repeat(10) + "😀".repeat(100) + "\u0000é".repeat(100)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strings")
    void roundTrip(String name, String want) throws SQLException {
        try (PreparedStatement s = conn.prepareStatement("INSERT INTO foo (s) VALUES (?)")) {
            s.setString(1, want);
            assertEquals(1, s.executeUpdate());
        }
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT s FROM foo")) {
            assertTrue(rs.next());
            assertEquals(want, rs.getString(1));
        }
    }
}

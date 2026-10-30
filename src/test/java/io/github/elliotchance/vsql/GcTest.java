package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * libvsql initializes its garbage collector on the thread that loads it, which
 * the collector treats as the main thread (on macOS, it aborts if that thread
 * has exited and a new thread reuses its ID). The collector must still work
 * after the thread that first used the driver has exited. Each test class runs
 * in its own JVM (see pom.xml), so this is the first use of libvsql.
 */
class GcTest {
    static void run(String sql, int times) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:")) {
            for (int i = 0; i < times; i++) {
                try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                    rs.next();
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void firstCallFromAThreadThatExits() throws Exception {
        Thread first = Thread.ofPlatform().start(() -> {
            try {
                run("VALUES 1", 1);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        first.join();

        // A large literal makes each statement allocate enough for the garbage
        // collector to run many times.
        String sql = "VALUES CASE WHEN '" + "x".repeat(5_000) + "' = '' THEN 0 ELSE 1 END";
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ts.add(Thread.ofPlatform().start(() -> {
                try {
                    run(sql, 20);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(List.of(), errors);
    }
}

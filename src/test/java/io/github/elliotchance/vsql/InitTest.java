package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;

/**
 * libvsql initializes itself in the first routine that's called, which isn't
 * thread safe (https://github.com/elliotchance/vsql/issues/710). Each test
 * class runs in its own JVM (see pom.xml), so this is the first use of libvsql.
 */
class InitTest {
    /** Many threads open their first connection at the same time. */
    @Test
    void concurrentFirstUse() throws Exception {
        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            ts.add(Thread.ofPlatform().start(() -> {
                try {
                    barrier.await();
                    try (Connection c = DriverManager.getConnection("jdbc:vsql::memory:");
                            ResultSet rs = c.createStatement().executeQuery("VALUES 1")) {
                        rs.next();
                        assertEquals(1, rs.getInt(1));
                    }
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

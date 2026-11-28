package io.github.elliotchance.vsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The versions of the driver and libvsql, see Version. */
class VersionTest {
    static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void driver() {
        assertEquals("0.0.0", Version.driver(null));
        assertEquals("1.2.3", Version.driver("1.2.3"));
    }

    @Test
    void libvsql() {
        assertEquals("0.0.0", Version.libvsql(null));
        assertEquals("1.2.35", Version.libvsql(stream("v1.2.35\n")));
        assertEquals("1.2.35", Version.libvsql(stream("1.2.35")));
        assertEquals("0.0.0", Version.libvsql(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("can't read");
            }
        }));
    }

    @Test
    void part() {
        assertEquals(1, Version.part("1.2.3", 0));
        assertEquals(2, Version.part("1.2.3-SNAPSHOT", 1));
        assertEquals(0, Version.part("1", 1));
        assertEquals(0, Version.part("x.y", 0));
    }
}
